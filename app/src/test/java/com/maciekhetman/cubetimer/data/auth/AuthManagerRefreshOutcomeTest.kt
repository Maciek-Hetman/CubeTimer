package com.maciekhetman.cubetimer.data.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.remote.CubeSyncApiClient
import com.maciekhetman.cubetimer.data.remote.TokenRefresher
import com.maciekhetman.cubetimer.data.remote.dto.AuthResponse
import com.maciekhetman.cubetimer.data.remote.dto.ChangePasswordRequest
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
import com.maciekhetman.cubetimer.model.currentUser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * What a failed refresh means for the session at startup restore: only a definitive rejection of
 * the refresh token ends the session; going offline or hitting a server hiccup must keep the user
 * logged in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AuthManagerRefreshOutcomeTest {

    private lateinit var database: CubeDatabase
    private lateinit var storage: ThreadSafeFakeTokenStorage
    private lateinit var apiClient: FakeApiClient
    private val user = RotatingRefreshBackend.cachedUser

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = CubeDatabase.createInMemory(context)
        storage = ThreadSafeFakeTokenStorage()
        apiClient = FakeApiClient()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun authResponse(access: String, refresh: String) = AuthResponse(
        accessToken = access,
        refreshToken = refresh,
        user = UserDto(id = user.id, email = user.email, userRole = "user", emailVerified = true)
    )

    private fun TestScope.newAuthManager() = AuthManagerImpl(
        apiClient = apiClient,
        tokenStorage = storage,
        database = database,
        ioDispatcher = StandardTestDispatcher(testScheduler),
        authScope = backgroundScope,
        autoInitialize = false
    )

    /** A stored session as an earlier successful refresh leaves it: "access-1" and "refresh-1" for the cached user. */
    private fun seedLoggedInSession() {
        storage.seed(accessToken = "access-1", refreshToken = "refresh-1", user = user)
    }

    private fun assertSessionKept(authManager: AuthManagerImpl) {
        assertTrue(authManager.authState.value is AuthState.Authenticated)
        assertEquals("refresh-1", storage.getRefreshToken())
        assertEquals("access-1", storage.getAccessToken())
        assertEquals(0, storage.clearAuthDataCalls.get())
    }

    // --- Startup restore through CubeSyncApiClient (no shared refresher) ---

    @Test
    fun initialize_onNetworkFailures_keepsTheSession() = runTest {
        val transientFailures = listOf<Throwable>(
            AuthException.NetworkError("offline"),
            IOException("timeout"),
            AuthException.RateLimited(),
            AuthException.ApiError(errorCode = "internal_error", message = "boom", httpStatusCode = 500),
            AuthException.SerializationError()
        )
        for (failure in transientFailures) {
            seedLoggedInSession()
            apiClient.onRefresh = { throw failure }
            val authManager = newAuthManager()

            authManager.initialize()

            assertSessionKept(authManager)
        }
    }

    @Test
    fun initialize_onDefinitiveRejection_clearsTheSession() = runTest {
        val rejections = listOf<AuthException>(
            AuthException.InvalidRefreshToken(),
            AuthException.RefreshTokenReused(),
            AuthException.Unauthorized(),
            AuthException.InvalidCredentials(),
            AuthException.Forbidden(),
            AuthException.ApiError(errorCode = "unknown_error", message = "conflict", httpStatusCode = 409)
        )
        for (rejection in rejections) {
            seedLoggedInSession()
            apiClient.onRefresh = { throw rejection }
            val authManager = newAuthManager()

            authManager.initialize()

            assertEquals(AuthState.Guest, authManager.authState.value)
            assertNull("$rejection must clear the refresh token", storage.getRefreshToken())
            assertNull(storage.getAccessToken())
        }
    }

    @Test
    fun initialize_success_publishesTheRefreshedUser() = runTest {
        // No cached identity: the published user can only have come from the refresh response.
        storage.seed(accessToken = null, refreshToken = "refresh-1", user = null)
        apiClient.onRefresh = { authResponse("access-2", "refresh-2") }
        val authManager = newAuthManager()

        authManager.initialize()

        assertEquals("refresh-2", storage.getRefreshToken())
        assertEquals(user.id, authManager.authState.value.currentUser?.id)
    }

    @Test
    fun initialize_onServerErrorOrRateLimit_keepsTheCachedSession() = runTest {
        val serverError = AuthException.ApiError(errorCode = "internal_error", message = "boom", httpStatusCode = 500)
        for (failure in listOf<Throwable>(serverError, AuthException.RateLimited())) {
            storage.seed(accessToken = null, refreshToken = "refresh-1", user = user)
            apiClient.onRefresh = { throw failure }
            val authManager = newAuthManager()

            authManager.initialize()

            assertEquals(user.id, authManager.authState.value.currentUser?.id)
            assertEquals("refresh-1", storage.getRefreshToken())
        }
    }

    @Test
    fun initialize_cancelledDuringTheRefresh_doesNotTurnIntoGuest() = runTest {
        // No cached identity, so swallowing the cancellation as a "network error" would have set Guest.
        storage.seed(accessToken = null, refreshToken = "refresh-0", user = null)
        val gate = CompletableDeferred<AuthResponse>()
        apiClient.onRefresh = { gate.await() }
        val authManager = newAuthManager()

        val restore = launch { authManager.initialize() }
        runCurrent()
        restore.cancel()
        runCurrent()

        assertTrue(restore.isCancelled)
        assertEquals(AuthState.Loading, authManager.authState.value)
        assertEquals("refresh-0", storage.getRefreshToken())
        authManager.awaitInitialized() // the cancelled run must not leave anyone waiting
    }

    // --- The same outcomes through the shared TokenRefresher (what the app runs) ---

    private fun withRefresherAgainst(
        server: MockWebServer,
        block: suspend (AuthManagerImpl) -> Unit
    ) = runBlocking {
        val authManager = AuthManagerImpl(
            apiClient = apiClient,
            tokenStorage = storage,
            database = database,
            ioDispatcher = Dispatchers.IO,
            authScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            autoInitialize = false,
            tokenRefresher = TokenRefresher(storage, server.url("/").toString())
        )
        block(authManager)
    }

    private fun refresherServer(vararg responses: MockResponse) = MockWebServer().apply {
        responses.forEach { enqueue(it) }
        start()
    }

    private val refreshSuccessBody = """
        {"access_token":"access-2","refresh_token":"refresh-2","token_type":"Bearer","expires_in":900,
         "user":{"id":"${RotatingRefreshBackend.USER_ID}","email":"${RotatingRefreshBackend.USER_EMAIL}",
                 "user_role":"user","email_verified":true}}
    """.trimIndent()

    @Test
    fun refresher_initialize_onServerErrorRateLimitOrDroppedConnection_keepsTheSession() {
        val outages = listOf(
            MockResponse().setResponseCode(500).setBody("""{"error":{"code":"internal_error"}}"""),
            MockResponse().setResponseCode(429).setBody("""{"error":{"code":"rate_limited"}}"""),
            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
        )
        for (outage in outages) {
            storage.seed(accessToken = "access-1", refreshToken = "refresh-1", user = user)
            val server = refresherServer(outage)
            try {
                withRefresherAgainst(server) { authManager ->
                    authManager.initialize()

                    assertTrue(authManager.authState.value is AuthState.Authenticated)
                    assertEquals("refresh-1", storage.getRefreshToken())
                    assertEquals(0, storage.clearAuthDataCalls.get())
                }
            } finally {
                server.shutdown()
            }
        }
    }

    @Test
    fun refresher_initialize_whenTheRefreshTokenIsRejected_clearsTheSession() {
        val rejections = listOf(
            MockResponse().setResponseCode(401).setBody("""{"error":{"code":"invalid_refresh_token"}}"""),
            MockResponse().setResponseCode(409).setBody("""{"error":{"code":"refresh_token_reused"}}"""),
            MockResponse().setResponseCode(400).setBody("""{"error":{"code":"bad_request"}}"""),
            MockResponse().setResponseCode(403).setBody("""{"error":{"code":"forbidden"}}""")
        )
        for (rejection in rejections) {
            storage.seed(accessToken = "access-1", refreshToken = "refresh-1", user = user)
            val server = refresherServer(rejection)
            try {
                withRefresherAgainst(server) { authManager ->
                    authManager.initialize()

                    assertEquals(AuthState.Guest, authManager.authState.value)
                    assertNull(storage.getRefreshToken())
                    assertNull(storage.getAccessToken())
                }
            } finally {
                server.shutdown()
            }
        }
    }

    @Test
    fun refresher_initialize_success_storesRotatedTokensAndPublishesTheUser() {
        storage.seed(accessToken = "access-1", refreshToken = "refresh-1", user = user)
        val server = refresherServer(MockResponse().setResponseCode(200).setBody(refreshSuccessBody))
        try {
            withRefresherAgainst(server) { authManager ->
                authManager.initialize()

                assertEquals(user.id, authManager.authState.value.currentUser?.id)
                assertEquals("access-2", storage.getAccessToken())
                assertEquals("refresh-2", storage.getRefreshToken())
                assertTrue(authManager.authState.value is AuthState.Authenticated)
                // The refresher persisted the session; AuthManager must not have written it a second time.
                assertEquals(0, apiClient.refreshCalls)
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun refresher_initialize_keepsCachedIdentityOnOutageAndDropsItOnRejection() {
        storage.seed(accessToken = null, refreshToken = "refresh-1", user = user)
        val outage = refresherServer(MockResponse().setResponseCode(503))
        try {
            withRefresherAgainst(outage) { authManager ->
                authManager.initialize()

                assertEquals(user.id, authManager.authState.value.currentUser?.id)
                assertEquals("refresh-1", storage.getRefreshToken())
            }
        } finally {
            outage.shutdown()
        }

        val rejecting = refresherServer(
            MockResponse().setResponseCode(401).setBody("""{"error":{"code":"invalid_refresh_token"}}""")
        )
        try {
            withRefresherAgainst(rejecting) { authManager ->
                authManager.initialize()

                assertEquals(AuthState.Guest, authManager.authState.value)
                assertNull(storage.getRefreshToken())
            }
        } finally {
            rejecting.shutdown()
        }
    }

    // --- Fakes ---

    private class FakeApiClient : CubeSyncApiClient {
        var refreshCalls = 0
        var onRefresh: suspend () -> AuthResponse = { throw NotImplementedError() }

        override suspend fun refreshToken(refreshToken: String): AuthResponse {
            refreshCalls++
            return onRefresh()
        }

        override suspend fun sync(request: SyncRequest): SyncResponse = SyncResponse()
        override suspend fun snapshot(request: SnapshotRequest) = SnapshotResponse()
        override suspend fun register(request: RegisterRequest): StatusResponse = throw NotImplementedError()
        override suspend fun resendVerificationEmail(email: String): StatusResponse = throw NotImplementedError()
        override suspend fun verifyEmail(token: String): AuthResponse = throw NotImplementedError()
        override suspend fun login(request: LoginRequest): AuthResponse = throw NotImplementedError()
        override suspend fun logout(refreshToken: String) = Unit
        override suspend fun requestPasswordReset(email: String): StatusResponse = throw NotImplementedError()
        override suspend fun confirmPasswordReset(token: String, newPassword: String): AuthResponse = throw NotImplementedError()
        override suspend fun changePassword(request: ChangePasswordRequest) = Unit
        override suspend fun deleteAccount() = Unit
    }
}
