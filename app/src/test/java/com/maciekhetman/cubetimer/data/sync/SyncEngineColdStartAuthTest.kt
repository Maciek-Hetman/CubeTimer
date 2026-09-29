package com.maciekhetman.cubetimer.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.maciekhetman.cubetimer.data.auth.AuthManager
import com.maciekhetman.cubetimer.data.auth.AuthManagerImpl
import com.maciekhetman.cubetimer.data.auth.TokenStorage
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import com.maciekhetman.cubetimer.data.local.mapper.toUpsertMutation
import com.maciekhetman.cubetimer.data.remote.CubeSyncApiClient
import com.maciekhetman.cubetimer.data.remote.NetworkModule
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
import com.maciekhetman.cubetimer.data.sync.work.SyncWorker
import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.model.User
import com.maciekhetman.cubetimer.model.UserRole
import com.maciekhetman.cubetimer.model.currentUser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * When WorkManager cold-starts the process to run sync, [SyncEngineImpl.sync] runs while the auth
 * manager is still restoring the session. It must wait for that to finish - including the startup
 * token refresh, since only then is there an access token - instead of seeing
 * [AuthState.Loading] and returning [SyncResult.NoOp] (which the worker reports as success).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SyncEngineColdStartAuthTest {

    private lateinit var context: Context
    private lateinit var database: CubeDatabase
    private lateinit var tokenStorage: FakeTokenStorage
    private lateinit var apiClient: FakeApiClient

    private val user = User(
        id = "user-cold-start",
        email = "cold@example.com",
        userRole = UserRole.USER,
        emailVerified = true,
        displayName = "Cold Start"
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = CubeDatabase.createInMemory(context)
        tokenStorage = FakeTokenStorage()
        apiClient = FakeApiClient(tokenStorage)
    }

    @After
    fun tearDown() {
        database.close()
    }

    /**
     * The engine runs on the test scheduler, so whether a sync has already read the auth state and
     * started (or no-opped) is decided deterministically by runCurrent(), not by racing an IO thread.
     */
    private fun TestScope.newEngine(authManager: AuthManager) = SyncEngineImpl(
        apiClient = apiClient,
        tokenStorage = tokenStorage,
        database = database,
        authManager = authManager,
        ioDispatcher = StandardTestDispatcher(testScheduler),
        authInitTimeoutMillis = AUTH_INIT_TIMEOUT_MILLIS
    )

    private suspend fun enqueuePendingSolve(ownerId: String): String {
        val solve = SolveEntity(
            id = "solve-cold-start",
            ownerId = ownerId,
            durationMs = 11_000L,
            solvedAt = "2026-09-29T08:00:00.000Z"
        )
        database.solveDao().insert(solve)
        database.syncOutboxDao().enqueue(
            solve.toUpsertMutation(clientTime = "2026-09-29T08:00:00.000Z", json = NetworkModule.json)
        )
        return solve.id
    }

    @Test
    fun sync_whileAuthLoading_waitsForInitializationToCompleteThenUploads() = runTest {
        val solveId = enqueuePendingSolve(user.id)
        val authManager = FakeAuthManager(AuthState.Loading, initialization = CompletableDeferred())
        val engine = newEngine(authManager)

        val sync = async { engine.sync() }
        runCurrent()
        assertFalse("sync must not no-op while auth is Loading", sync.isCompleted)
        assertFalse(engine.isSyncing.value)

        // The cached identity is published before the startup refresh returns: still no token, so
        // leaving Loading alone must not release the sync.
        authManager.authStateFlow.value = AuthState.Authenticated(user)
        runCurrent()
        assertFalse("sync must wait for initialization, not just for a non-Loading state", sync.isCompleted)
        assertFalse(engine.isSyncing.value)
        assertTrue(apiClient.syncRequests.isEmpty())

        authManager.initialization!!.complete(Unit)
        val result = sync.await()

        assertTrue("expected Success but was $result", result is SyncResult.Success)
        assertEquals(1, apiClient.syncRequests.size)
        assertEquals(listOf(solveId), apiClient.syncRequests.single().mutations.map { it.entityId })
    }

    @Test
    fun sync_whileLoading_withDefaultAwaitInitialized_proceedsOnceStateIsResolved() = runTest {
        enqueuePendingSolve(user.id)
        // No override: exercises AuthManager's default awaitInitialized (wait for non-Loading).
        val authManager = FakeAuthManager(AuthState.Loading)
        val engine = newEngine(authManager)

        val sync = async { engine.sync() }
        runCurrent()
        assertFalse(sync.isCompleted)

        authManager.authStateFlow.value = AuthState.Authenticated(user)
        val result = sync.await()

        assertTrue("expected Success but was $result", result is SyncResult.Success)
        assertEquals(1, apiClient.syncRequests.size)
    }

    @Test
    fun sync_withExplicitOwnerWhileAuthInitializing_stillWaitsForInitialization() = runTest {
        enqueuePendingSolve(user.id)
        val authManager = FakeAuthManager(AuthState.Authenticated(user), initialization = CompletableDeferred())
        val engine = newEngine(authManager)

        // An explicit owner needs the same access token, so it must not skip the wait.
        val sync = async { engine.sync(user.id) }
        runCurrent()
        assertFalse(sync.isCompleted)
        assertFalse("sync must not start before initialization completes", engine.isSyncing.value)
        assertTrue(apiClient.syncRequests.isEmpty())

        authManager.initialization!!.complete(Unit)
        val result = sync.await()

        assertTrue("expected Success but was $result", result is SyncResult.Success)
        assertEquals(1, apiClient.syncRequests.size)
    }

    @Test
    fun sync_whenAuthInitializationNeverCompletes_timesOutWithRetryableResultWithoutCallingApi() = runTest {
        enqueuePendingSolve(user.id)
        val authManager = FakeAuthManager(AuthState.Loading, initialization = CompletableDeferred())
        val engine = newEngine(authManager)

        val result = engine.sync()

        assertTrue("expected Offline (retryable) but was $result", result is SyncResult.Offline)
        assertEquals(AUTH_INIT_TIMEOUT_MILLIS, currentTime)
        assertTrue(apiClient.syncRequests.isEmpty())
        assertEquals(SyncStatus.OFFLINE, engine.syncStatus.value)
        // Nothing was attempted, so the queued mutation is untouched.
        val pending = database.syncOutboxDao().getAllPendingForOwner(user.id).single()
        assertEquals(0, pending.attemptCount)
    }

    @Test
    fun syncWorker_whenAuthInitializationTimesOut_retriesInsteadOfReportingSuccess() = runTest {
        enqueuePendingSolve(user.id)
        val authManager = FakeAuthManager(AuthState.Loading, initialization = CompletableDeferred())
        val engine = newEngine(authManager)
        val worker = TestListenableWorkerBuilder<SyncWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): ListenableWorker = SyncWorker(appContext, workerParameters, engine)
            })
            .build()

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        assertTrue(apiClient.syncRequests.isEmpty())
    }

    @Test
    fun sync_withExplicitGuestOwner_doesNotWaitForAuthInitialization() = runTest {
        val authManager = FakeAuthManager(AuthState.Loading, initialization = CompletableDeferred())
        val engine = newEngine(authManager)

        val result = engine.sync("guest")

        assertEquals(SyncResult.NoOp, result)
        assertEquals(0L, currentTime)
        assertTrue(apiClient.syncRequests.isEmpty())
    }

    @Test
    fun coldStart_realAuthManager_syncWaitsForStartupRefreshAndSendsWithFreshAccessToken() = runTest {
        enqueuePendingSolve(user.id)
        tokenStorage.storedRefreshToken = "stored-refresh"
        tokenStorage.storedCachedUser = user
        val authManager = newRealAuthManager()
        val engine = newEngine(authManager)

        val sync = async { engine.sync() }
        runCurrent()

        // Cached identity published, startup refresh still in flight, no access token yet.
        assertEquals(user.id, authManager.authState.value.currentUser?.id)
        assertEquals(1, apiClient.refreshCallCount)
        assertEquals(null, tokenStorage.getAccessToken())
        assertFalse("sync must not finish (NoOp) before the startup refresh returns", sync.isCompleted)
        assertFalse("sync must not start before the startup refresh returns", engine.isSyncing.value)
        assertTrue(apiClient.syncRequests.isEmpty())

        apiClient.refreshResult.complete(
            AuthResponse(
                accessToken = "fresh-access",
                refreshToken = "rotated-refresh",
                user = UserDto(id = user.id, email = user.email, userRole = "user", emailVerified = true)
            )
        )
        val result = sync.await()

        assertTrue("expected Success but was $result", result is SyncResult.Success)
        assertEquals(listOf<String?>("fresh-access"), apiClient.accessTokensSeenBySync)
        // Only the startup refresh ran - the sync never raced it with a second one.
        assertEquals(1, apiClient.refreshCallCount)
        assertEquals("rotated-refresh", tokenStorage.storedRefreshToken)
    }

    @Test
    fun coldStart_realAuthManager_asGuest_returnsNoOpPromptly() = runTest {
        tokenStorage.storedRefreshToken = null
        val authManager = newRealAuthManager()
        val engine = newEngine(authManager)

        val result = engine.sync()

        assertEquals(SyncResult.NoOp, result)
        assertEquals(AuthState.Guest, authManager.authState.value)
        assertEquals(0L, currentTime)
        assertEquals(0, apiClient.refreshCallCount)
        assertTrue(apiClient.syncRequests.isEmpty())
        assertEquals(SyncStatus.UNAUTHENTICATED, engine.syncStatus.value)
    }

    /** Built the way CubeTimerApplication builds it: auto-initializing, asynchronously. */
    private fun TestScope.newRealAuthManager() = AuthManagerImpl(
        apiClient = apiClient,
        tokenStorage = tokenStorage,
        database = database,
        ioDispatcher = StandardTestDispatcher(testScheduler),
        authScope = backgroundScope,
        autoInitialize = true
    )

    private companion object {
        const val AUTH_INIT_TIMEOUT_MILLIS = 5_000L
    }

    private class FakeAuthManager(
        initialState: AuthState,
        /** When set, initialization completes only once this does (models the startup refresh). */
        val initialization: CompletableDeferred<Unit>? = null
    ) : AuthManager {
        val authStateFlow = MutableStateFlow(initialState)
        override val authState: StateFlow<AuthState> = authStateFlow
        override val currentUser: User? get() = authState.value.currentUser

        override suspend fun awaitInitialized() {
            if (initialization != null) initialization.await() else super.awaitInitialized()
        }

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

    private class FakeApiClient(private val tokenStorage: TokenStorage) : CubeSyncApiClient {
        val syncRequests = mutableListOf<SyncRequest>()
        /** The access token the auth interceptor would have attached to each sync request. */
        val accessTokensSeenBySync = mutableListOf<String?>()
        var refreshCallCount = 0
        val refreshResult = CompletableDeferred<AuthResponse>()

        override suspend fun refreshToken(refreshToken: String): AuthResponse {
            refreshCallCount++
            return refreshResult.await()
        }

        override suspend fun sync(request: SyncRequest, authToken: String?): SyncResponse {
            syncRequests += request
            accessTokensSeenBySync += tokenStorage.getAccessToken()
            return SyncResponse()
        }

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
        override fun getUserRole(): String? = storedCachedUser?.userRole?.let { if (it == UserRole.ADMIN) "admin" else "user" }
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
        override fun getDeviceId(): String = "device-cold-start"
        override fun clearAuthData() {
            setAccessToken(null)
            storedRefreshToken = null
            storedCachedUser = null
        }
        override fun clearAll() = clearAuthData()
    }
}
