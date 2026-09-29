package com.maciekhetman.cubetimer.data.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.remote.CubeSyncApiClient
import com.maciekhetman.cubetimer.data.remote.dto.AuthResponse
import com.maciekhetman.cubetimer.data.remote.dto.ChangePasswordRequest
import com.maciekhetman.cubetimer.data.remote.dto.GoogleAuthRequest
import com.maciekhetman.cubetimer.data.remote.dto.LoginRequest
import com.maciekhetman.cubetimer.data.remote.dto.RegisterRequest
import com.maciekhetman.cubetimer.data.remote.dto.SnapshotRequest
import com.maciekhetman.cubetimer.data.remote.dto.SnapshotResponse
import com.maciekhetman.cubetimer.data.remote.dto.StatusResponse
import com.maciekhetman.cubetimer.data.remote.dto.SyncRequest
import com.maciekhetman.cubetimer.data.remote.dto.SyncResponse
import com.maciekhetman.cubetimer.data.remote.dto.UserDto
import com.maciekhetman.cubetimer.model.AuthException
import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.model.User
import com.maciekhetman.cubetimer.model.UserRole
import com.maciekhetman.cubetimer.model.currentUser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * [AuthManagerImpl.awaitInitialized] must return only once session restore has fully finished -
 * after the startup refresh, not when the cached identity is first published - and must never
 * wait on an initialization that nobody started.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AuthManagerAwaitInitializedTest {

    private lateinit var database: CubeDatabase
    private lateinit var tokenStorage: FakeTokenStorage
    private lateinit var apiClient: FakeApiClient

    private val cachedUser = User(
        id = "cached-user",
        email = "cached@example.com",
        userRole = UserRole.USER,
        emailVerified = true,
        displayName = "Cached"
    )

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = CubeDatabase.createInMemory(context)
        tokenStorage = FakeTokenStorage()
        apiClient = FakeApiClient()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun TestScope.newAuthManager(
        autoInitialize: Boolean,
        authScope: CoroutineScope = backgroundScope
    ) = AuthManagerImpl(
        apiClient = apiClient,
        tokenStorage = tokenStorage,
        database = database,
        ioDispatcher = StandardTestDispatcher(testScheduler),
        authScope = authScope,
        autoInitialize = autoInitialize
    )

    private fun refreshedSession(accessToken: String, refreshToken: String) = AuthResponse(
        accessToken = accessToken,
        refreshToken = refreshToken,
        user = UserDto(id = cachedUser.id, email = cachedUser.email, userRole = "user", emailVerified = true)
    )

    @Test
    fun awaitInitialized_withCachedUser_returnsOnlyAfterStartupRefreshFinishes() = runTest {
        tokenStorage.storedRefreshToken = "stored-refresh"
        tokenStorage.storedCachedUser = cachedUser
        val gate = apiClient.nextRefreshGate()
        val authManager = newAuthManager(autoInitialize = true)

        val waiter = async { authManager.awaitInitialized() }
        runCurrent()

        // The cached identity is surfaced first (offline-first), but the refresh is still running.
        assertEquals(cachedUser.id, (authManager.authState.value as AuthState.Authenticated).user.id)
        assertNull(tokenStorage.getAccessToken())
        assertFalse("awaitInitialized must not return before the refresh finishes", waiter.isCompleted)

        gate.complete(Result.success(refreshedSession("fresh-access", "rotated-refresh")))
        runCurrent()

        assertTrue(waiter.isCompleted)
        assertEquals("fresh-access", tokenStorage.getAccessToken())
        assertEquals(1, apiClient.refreshCallCount)
    }

    @Test
    fun awaitInitialized_whenRefreshIsRejected_returnsWithGuest() = runTest {
        tokenStorage.storedRefreshToken = "revoked-refresh"
        tokenStorage.storedCachedUser = cachedUser
        val gate = apiClient.nextRefreshGate()
        val authManager = newAuthManager(autoInitialize = true)

        val waiter = async { authManager.awaitInitialized() }
        runCurrent()
        assertFalse(waiter.isCompleted)

        gate.complete(Result.failure(AuthException.InvalidRefreshToken()))
        waiter.await()

        assertEquals(AuthState.Guest, authManager.authState.value)
        assertNull(tokenStorage.storedRefreshToken)
    }

    @Test
    fun awaitInitialized_whenRefreshFailsOnNetwork_returnsKeepingCachedIdentity() = runTest {
        tokenStorage.storedRefreshToken = "stored-refresh"
        tokenStorage.storedCachedUser = cachedUser
        val gate = apiClient.nextRefreshGate()
        val authManager = newAuthManager(autoInitialize = true)

        val waiter = async { authManager.awaitInitialized() }
        runCurrent()
        assertFalse(waiter.isCompleted)

        gate.complete(Result.failure(IOException("timeout")))
        waiter.await()

        assertEquals(cachedUser.id, authManager.authState.value.currentUser?.id)
        assertEquals("stored-refresh", tokenStorage.storedRefreshToken)
    }

    @Test
    fun awaitInitialized_withoutRefreshToken_returnsPromptlyAsGuest() = runTest {
        tokenStorage.storedRefreshToken = null
        val authManager = newAuthManager(autoInitialize = true)

        authManager.awaitInitialized()

        assertEquals(AuthState.Guest, authManager.authState.value)
        assertEquals(0L, currentTime)
        assertEquals(0, apiClient.refreshCallCount)
    }

    @Test
    fun awaitInitialized_withoutAutoInitialize_andNoInitializeCall_returnsImmediately() = runTest {
        tokenStorage.storedRefreshToken = "stored-refresh"
        val authManager = newAuthManager(autoInitialize = false)

        // Nothing is restoring a session, so there is nothing to wait for - it must not hang.
        authManager.awaitInitialized()

        assertEquals(AuthState.Loading, authManager.authState.value)
        assertEquals(0L, currentTime)
        assertEquals(0, apiClient.refreshCallCount)
    }

    @Test
    fun awaitInitialized_withoutAutoInitialize_waitsForAnExplicitInitialize() = runTest {
        tokenStorage.storedRefreshToken = "stored-refresh"
        tokenStorage.storedCachedUser = cachedUser
        val gate = apiClient.nextRefreshGate()
        val authManager = newAuthManager(autoInitialize = false)

        val init = launch { authManager.initialize() }
        runCurrent()
        val waiter = async { authManager.awaitInitialized() }
        runCurrent()
        assertFalse(waiter.isCompleted)

        gate.complete(Result.success(refreshedSession("fresh-access", "rotated-refresh")))
        waiter.await()

        assertTrue(init.isCompleted)
        assertEquals("fresh-access", tokenStorage.getAccessToken())
    }

    @Test
    fun awaitInitialized_waitsForARepeatedInitializeStillInFlight() = runTest {
        tokenStorage.storedRefreshToken = "stored-refresh"
        tokenStorage.storedCachedUser = cachedUser
        val firstGate = apiClient.nextRefreshGate()
        val authManager = newAuthManager(autoInitialize = true)
        firstGate.complete(Result.success(refreshedSession("access-1", "refresh-1")))
        authManager.awaitInitialized()
        assertEquals("access-1", tokenStorage.getAccessToken())

        // A later initialize() (e.g. called again by some caller) is in flight: wait for it too.
        val secondGate = apiClient.nextRefreshGate()
        launch { authManager.initialize() }
        runCurrent()
        val waiter = async { authManager.awaitInitialized() }
        runCurrent()
        assertFalse(waiter.isCompleted)

        secondGate.complete(Result.success(refreshedSession("access-2", "refresh-2")))
        waiter.await()

        assertEquals("access-2", tokenStorage.getAccessToken())
        assertEquals(2, apiClient.refreshCallCount)
    }

    @Test
    fun awaitInitialized_whenAutoInitializeNeverStarts_doesNotHang() = runTest {
        tokenStorage.storedRefreshToken = "stored-refresh"
        // A scope that is already cancelled: the automatic launch is cancelled before it starts.
        val deadScope = CoroutineScope(Job().apply { cancel() })
        val authManager = newAuthManager(autoInitialize = true, authScope = deadScope)

        authManager.awaitInitialized()

        assertEquals(0L, currentTime)
        assertEquals(0, apiClient.refreshCallCount)
    }

    private class FakeApiClient : CubeSyncApiClient {
        private val gates = ArrayDeque<CompletableDeferred<Result<AuthResponse>>>()
        var refreshCallCount = 0

        /** The next refreshToken call suspends until the returned gate is completed. */
        fun nextRefreshGate(): CompletableDeferred<Result<AuthResponse>> =
            CompletableDeferred<Result<AuthResponse>>().also { gates.addLast(it) }

        override suspend fun refreshToken(refreshToken: String): AuthResponse {
            refreshCallCount++
            return gates.removeFirst().await().getOrThrow()
        }

        override suspend fun sync(request: SyncRequest, authToken: String?): SyncResponse = SyncResponse()
        override suspend fun snapshot(request: SnapshotRequest, authToken: String?) = SnapshotResponse()
        override suspend fun register(request: RegisterRequest): StatusResponse = throw NotImplementedError()
        override suspend fun resendVerificationEmail(email: String): StatusResponse = throw NotImplementedError()
        override suspend fun verifyEmail(token: String): AuthResponse = throw NotImplementedError()
        override suspend fun login(request: LoginRequest): AuthResponse = throw NotImplementedError()
        override suspend fun logout(refreshToken: String) = Unit
        override suspend fun requestPasswordReset(email: String): StatusResponse = throw NotImplementedError()
        override suspend fun confirmPasswordReset(token: String, newPassword: String): AuthResponse = throw NotImplementedError()
        override suspend fun loginWithGoogle(request: GoogleAuthRequest): AuthResponse = throw NotImplementedError()
        override suspend fun linkGoogle(request: GoogleAuthRequest, authToken: String?) = Unit
        override suspend fun getCurrentUser(authToken: String?): UserDto = throw NotImplementedError()
        override suspend fun changePassword(request: ChangePasswordRequest, authToken: String?) = Unit
        override suspend fun deleteAccount(authToken: String?) = Unit
    }

    /** In-memory token storage; like EncryptedTokenStorage, the access token starts out absent. */
    private class FakeTokenStorage : TokenStorage {
        var storedAccessToken: String? = null
        var storedRefreshToken: String? = null
        var storedCachedUser: User? = null

        private val accessToken = MutableStateFlow<String?>(null)
        override val accessTokenFlow: StateFlow<String?> = accessToken

        override fun getAccessToken(): String? = storedAccessToken
        override fun setAccessToken(token: String?) {
            storedAccessToken = token
            accessToken.value = token
        }
        override fun getRefreshToken(): String? = storedRefreshToken
        override fun setRefreshToken(token: String?) { storedRefreshToken = token }
        override fun getUserId(): String? = storedCachedUser?.id
        override fun getUserEmail(): String? = storedCachedUser?.email
        override fun getUserRole(): String? = storedCachedUser?.let { if (it.userRole == UserRole.ADMIN) "admin" else "user" }
        override fun isUserEmailVerified(): Boolean = storedCachedUser?.emailVerified ?: false
        override fun getDisplayName(): String? = storedCachedUser?.displayName
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
            storedRefreshToken = refreshToken
            storedCachedUser = User(
                id = userId,
                email = userEmail,
                displayName = displayName,
                emailVerified = emailVerified,
                userRole = UserRole.fromString(userRole)
            )
        }
        override fun saveUser(user: User) { storedCachedUser = user }
        override fun getDeviceId(): String = "device-await-init"
        override fun clearAuthData() {
            setAccessToken(null)
            storedRefreshToken = null
            storedCachedUser = null
        }
        override fun clearAll() = clearAuthData()
    }
}
