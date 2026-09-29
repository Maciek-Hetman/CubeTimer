package com.maciekhetman.cubetimer.data.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.remote.AuthInterceptor
import com.maciekhetman.cubetimer.data.remote.NetworkModule
import com.maciekhetman.cubetimer.data.remote.TokenAuthenticator
import com.maciekhetman.cubetimer.data.remote.TokenRefresher
import com.maciekhetman.cubetimer.model.AuthState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

/**
 * The server rotates refresh tokens and revokes the whole family when an old one is presented
 * again, so the startup restore in [AuthManagerImpl] and the [TokenAuthenticator] (refreshing on a
 * 401) must never spend the same refresh token. Both go through one [TokenRefresher]; these tests
 * race them against a server that enforces rotation.
 */
@RunWith(RobolectricTestRunner::class)
class AuthManagerRefreshCoordinationTest {

    private lateinit var server: MockWebServer
    private lateinit var backend: RotatingRefreshBackend
    private lateinit var database: CubeDatabase
    private lateinit var storage: ThreadSafeFakeTokenStorage
    private lateinit var refresher: TokenRefresher
    private lateinit var httpClient: OkHttpClient
    private val sessionExpiredNotifications = AtomicInteger(0)

    @Before
    fun setUp() {
        backend = RotatingRefreshBackend()
        server = MockWebServer().apply {
            dispatcher = backend
            start()
        }
        val context: Context = ApplicationProvider.getApplicationContext()
        database = CubeDatabase.createInMemory(context)
        storage = ThreadSafeFakeTokenStorage()

        val baseUrl = server.url("/").toString()
        refresher = TokenRefresher(storage, baseUrl)
        val authenticator = TokenAuthenticator(
            tokenStorage = storage,
            baseUrl = baseUrl,
            sessionExpirationListener = { sessionExpiredNotifications.incrementAndGet() },
            tokenRefresher = refresher
        )
        httpClient = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(storage))
            .authenticator(authenticator)
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
        database.close()
    }

    private fun newAuthManager(autoInitialize: Boolean = false) = AuthManagerImpl(
        apiClient = NetworkModule.provideCubeSyncApiClient(
            NetworkModule.provideAuthApiService(server.url("/").toString(), OkHttpClient())
        ),
        tokenStorage = storage,
        database = database,
        ioDispatcher = Dispatchers.IO,
        authScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        autoInitialize = autoInitialize,
        tokenRefresher = refresher
    )

    private fun getData(): Int {
        val request = Request.Builder().url(server.url("/v1/data")).get().build()
        return httpClient.newCall(request).execute().use { it.code }
    }

    private suspend fun awaitCondition(condition: () -> Boolean) {
        withTimeout(10_000) {
            while (!condition()) delay(10)
        }
    }

    private fun assertSessionSurvivedWithSingleRefresh(expectedGeneration: Int = 1) {
        assertEquals("exactly one network refresh per rotation", expectedGeneration, backend.refreshCalls.get())
        assertEquals("the server must never see a reused refresh token", 0, backend.reuseRejections.get())
        assertEquals("access-$expectedGeneration", storage.getAccessToken())
        assertEquals("refresh-$expectedGeneration", storage.getRefreshToken())
        assertEquals("no logout", 0, sessionExpiredNotifications.get())
        assertEquals("no logout", 0, storage.clearAuthDataCalls.get())
    }

    @Test
    fun startupRestoreInFlight_a401MeanwhileReusesItsSession() = runBlocking {
        // Cold start: refresh token and identity persisted, the memory-only access token is gone.
        storage.seed(accessToken = null, refreshToken = "refresh-0", user = RotatingRefreshBackend.cachedUser)
        backend.holdRefreshes()
        val authManager = newAuthManager()

        val restore = async(Dispatchers.Default) { authManager.initialize() }
        assertTrue("startup refresh never reached the server", backend.awaitRefreshArrived())

        // A request without a token gets a 401 while the startup refresh is still in flight.
        val request = async(Dispatchers.IO) { getData() }
        awaitCondition { backend.unauthorizedDataCalls.get() == 1 }
        delay(200) // let the authenticator run into the refresher's lock
        backend.releaseRefreshes()

        withTimeout(20_000) { restore.await() }
        assertEquals("the 401'd request is retried with the rotated access token", 200, withTimeout(20_000) { request.await() })
        assertSessionSurvivedWithSingleRefresh()
        assertEquals(RotatingRefreshBackend.USER_ID, authManager.currentUser?.id)
    }

    @Test
    fun authenticatorRefreshInFlight_startupRestoreReusesItsSession() = runBlocking {
        // The first authenticated request of this process carries an expired access token.
        storage.seed(accessToken = "expired-access", refreshToken = "refresh-0", user = RotatingRefreshBackend.cachedUser)
        backend.holdRefreshes()

        val request = async(Dispatchers.IO) { getData() }
        assertTrue("authenticator refresh never reached the server", backend.awaitRefreshArrived())

        // The startup restore begins while that refresh is in flight. Before the fix it sent
        // "refresh-0" a second time here, the server answered refresh_token_reused and the user
        // was logged out.
        val authManager = newAuthManager()
        val restore = async(Dispatchers.Default) { authManager.initialize() }
        awaitCondition { authManager.authState.value is AuthState.Authenticated }
        delay(200) // let the restore run into the refresher's lock
        backend.releaseRefreshes()

        withTimeout(20_000) { restore.await() }
        assertEquals(200, withTimeout(20_000) { request.await() })
        assertSessionSurvivedWithSingleRefresh()
        assertTrue(authManager.authState.value is AuthState.Authenticated)
        assertEquals(RotatingRefreshBackend.USER_ID, authManager.currentUser?.id)
    }

    @Test
    fun manyRequestsAndTheStartupRestoreRacing_stillOneRefresh() = runBlocking {
        storage.seed(accessToken = null, refreshToken = "refresh-0", user = RotatingRefreshBackend.cachedUser)
        backend.holdRefreshes()
        val authManager = newAuthManager()

        val restore = async(Dispatchers.Default) { authManager.initialize() }
        assertTrue(backend.awaitRefreshArrived())
        val requests = (1..8).map { async(Dispatchers.IO) { getData() } }
        awaitCondition { backend.unauthorizedDataCalls.get() == 8 }
        delay(200)
        backend.releaseRefreshes()

        withTimeout(20_000) { restore.await() }
        assertEquals(List(8) { 200 }, withTimeout(20_000) { requests.awaitAll() })
        assertSessionSurvivedWithSingleRefresh()
    }

    @Test
    fun concurrentInitializeCalls_shareOneRefresh() = runBlocking {
        storage.seed(accessToken = null, refreshToken = "refresh-0", user = RotatingRefreshBackend.cachedUser)
        backend.holdRefreshes()
        val authManager = newAuthManager()

        val calls = (1..5).map { async(Dispatchers.Default) { authManager.initialize() } }
        assertTrue(backend.awaitRefreshArrived())
        delay(300) // the other four are queued behind the running one
        backend.releaseRefreshes()

        withTimeout(20_000) { calls.awaitAll() }
        withTimeout(20_000) { authManager.awaitInitialized() }
        assertSessionSurvivedWithSingleRefresh()
        assertTrue(authManager.authState.value is AuthState.Authenticated)

        // Only initializations that overlap are merged: a later call is a new request.
        withTimeout(20_000) { authManager.initialize() }
        assertSessionSurvivedWithSingleRefresh(expectedGeneration = 2)
    }

    @Test
    fun cancelledStartupRestore_stillStoresTheRotatedTokens() = runBlocking {
        storage.seed(accessToken = null, refreshToken = "refresh-0", user = RotatingRefreshBackend.cachedUser)
        backend.holdRefreshes()
        val authManager = newAuthManager()

        val restore = async(Dispatchers.Default) { authManager.initialize() }
        assertTrue(backend.awaitRefreshArrived())
        // The request is on the wire: the server will rotate the token whether or not we still care.
        restore.cancel()
        delay(100)
        backend.releaseRefreshes()

        awaitCondition { storage.getRefreshToken() == "refresh-1" }
        assertEquals("access-1", storage.getAccessToken())
        assertEquals(1, backend.refreshCalls.get())
        assertEquals(0, storage.clearAuthDataCalls.get())
        withTimeout(20_000) { authManager.awaitInitialized() }
    }

    @Test
    fun explicitInitializeDuringTheAutomaticOne_sharesItsRefresh() = runBlocking {
        storage.seed(accessToken = null, refreshToken = "refresh-0", user = RotatingRefreshBackend.cachedUser)
        backend.holdRefreshes()

        val authManager = newAuthManager(autoInitialize = true)
        val explicit = async(Dispatchers.Default) { authManager.initialize() }
        assertTrue(backend.awaitRefreshArrived())
        delay(200)
        backend.releaseRefreshes()

        withTimeout(20_000) { explicit.await() }
        withTimeout(20_000) { authManager.awaitInitialized() }
        assertSessionSurvivedWithSingleRefresh()
        assertTrue(authManager.authState.value is AuthState.Authenticated)
    }
}
