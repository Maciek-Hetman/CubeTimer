package com.maciekhetman.cubetimer.data.remote

import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.data.auth.EncryptedTokenStorage
import com.maciekhetman.cubetimer.data.auth.SessionExpirationListener
import com.maciekhetman.cubetimer.data.auth.TokenStorage
import com.maciekhetman.cubetimer.model.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class TokenAuthenticatorStressTest {

    private lateinit var mockWebServer: MockWebServer

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `concurrency stress test - 50 simultaneous coroutines hitting 401 trigger exactly 1 refresh`() = runBlocking {
        val concurrency = 50
        val initialAccessToken = "expired-token-wave-1"
        val initialRefreshToken = "valid-refresh-token-1"
        val newAccessToken = "refreshed-access-token-1"
        val newRefreshToken = "rotated-refresh-token-1"

        val refreshCallCount = AtomicInteger(0)
        val dataCallCount = AtomicInteger(0)
        val expiredDataHits = AtomicInteger(0)
        val successfulDataHits = AtomicInteger(0)

        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val authHeader = request.getHeader("Authorization")

                return when {
                    path.startsWith("/v1/auth/refresh") -> {
                        refreshCallCount.incrementAndGet()
                        // Add artificial latency to simulate network transit and expand lock contention window
                        Thread.sleep(80)
                        val responseBody = """
                            {
                                "access_token": "$newAccessToken",
                                "refresh_token": "$newRefreshToken",
                                "token_type": "Bearer",
                                "expires_in": 900,
                                "user": {
                                    "id": "stress-user-1",
                                    "email": "stress@example.com",
                                    "user_role": "user",
                                    "email_verified": true
                                }
                            }
                        """.trimIndent()
                        MockResponse().setResponseCode(200).setBody(responseBody)
                    }
                    path.startsWith("/v1/data") -> {
                        dataCallCount.incrementAndGet()
                        if (authHeader == "Bearer $newAccessToken") {
                            successfulDataHits.incrementAndGet()
                            MockResponse().setResponseCode(200).setBody("""{"status":"ok"}""")
                        } else {
                            expiredDataHits.incrementAndGet()
                            MockResponse().setResponseCode(401).setBody("""{"error":{"code":"unauthorized"}}""")
                        }
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val fakeTokenStorage = MemoryTokenStorage()
        fakeTokenStorage.saveAuthSession(
            accessToken = initialAccessToken,
            refreshToken = initialRefreshToken,
            userId = "stress-user-1",
            userEmail = "stress@example.com",
            userRole = "user",
            emailVerified = true
        )

        val authenticator = TokenAuthenticator(
            tokenStorage = fakeTokenStorage,
            baseUrl = mockWebServer.url("/").toString()
        )

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(fakeTokenStorage))
            .authenticator(authenticator)
            .build()

        val threadPool = Executors.newFixedThreadPool(concurrency)
        val customDispatcher = threadPool.asCoroutineDispatcher()
        val startLatch = CountDownLatch(concurrency)

        val deferreds = (1..concurrency).map { index ->
            async(customDispatcher) {
                // Ensure all coroutines line up before firing
                startLatch.countDown()
                startLatch.await(5, TimeUnit.SECONDS)

                val req = Request.Builder()
                    .url(mockWebServer.url("/v1/data?req=$index"))
                    .get()
                    .build()

                val resp = okHttpClient.newCall(req).execute()
                val code = resp.code
                val body = resp.body?.string()
                resp.close()
                Pair(code, body)
            }
        }

        val results = deferreds.awaitAll()
        threadPool.shutdown()

        // Assert all 50 concurrent requests succeeded
        assertEquals(concurrency, results.size)
        results.forEachIndexed { i, res ->
            assertEquals("Request $i status code", 200, res.first)
            assertEquals("Request $i body", """{"status":"ok"}""", res.second)
        }

        // Assert EXACTLY 1 refresh call was made
        assertEquals("Refresh call count must be exactly 1", 1, refreshCallCount.get())

        // Assert token storage was updated
        assertEquals(newAccessToken, fakeTokenStorage.getAccessToken())
        assertEquals(newRefreshToken, fakeTokenStorage.getRefreshToken())

        // Assert initial 50 requests failed with 401, and 50 retries succeeded with 200
        assertEquals(concurrency, expiredDataHits.get())
        assertEquals(concurrency, successfulDataHits.get())
    }

    @Test
    fun `concurrency stress test with real EncryptedTokenStorage - thread-safe token reads and writes`() = runBlocking {
        val concurrency = 40
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val encryptedStorage = EncryptedTokenStorage(context, "stress_test_encrypted_prefs")
        encryptedStorage.clearAll()

        val initialAccessToken = "expired-token-real-storage"
        val initialRefreshToken = "valid-refresh-token-real"
        val newAccessToken = "refreshed-access-token-real"
        val newRefreshToken = "rotated-refresh-token-real"

        encryptedStorage.saveAuthSession(
            accessToken = initialAccessToken,
            refreshToken = initialRefreshToken,
            userId = "real-user-1",
            userEmail = "real@example.com",
            userRole = "user",
            emailVerified = true,
            displayName = "Speedcuber"
        )

        val refreshCallCount = AtomicInteger(0)

        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val authHeader = request.getHeader("Authorization")

                return when {
                    path.startsWith("/v1/auth/refresh") -> {
                        refreshCallCount.incrementAndGet()
                        Thread.sleep(60)
                        val responseBody = """
                            {
                                "access_token": "$newAccessToken",
                                "refresh_token": "$newRefreshToken",
                                "token_type": "Bearer",
                                "expires_in": 900,
                                "user": {
                                    "id": "real-user-1",
                                    "email": "real@example.com",
                                    "user_role": "user",
                                    "email_verified": true,
                                    "display_name": "Speedcuber"
                                }
                            }
                        """.trimIndent()
                        MockResponse().setResponseCode(200).setBody(responseBody)
                    }
                    path.startsWith("/v1/solves") -> {
                        if (authHeader == "Bearer $newAccessToken") {
                            MockResponse().setResponseCode(200).setBody("""{"solves":[]}""")
                        } else {
                            MockResponse().setResponseCode(401).setBody("""{"error":{"code":"unauthorized"}}""")
                        }
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val authenticator = TokenAuthenticator(
            tokenStorage = encryptedStorage,
            baseUrl = mockWebServer.url("/").toString()
        )

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(encryptedStorage))
            .authenticator(authenticator)
            .build()

        val threadPool = Executors.newFixedThreadPool(concurrency)
        val customDispatcher = threadPool.asCoroutineDispatcher()
        val startLatch = CountDownLatch(concurrency)

        val deferreds = (1..concurrency).map { index ->
            async(customDispatcher) {
                startLatch.countDown()
                startLatch.await(5, TimeUnit.SECONDS)

                val req = Request.Builder()
                    .url(mockWebServer.url("/v1/solves?offset=$index"))
                    .get()
                    .build()

                val resp = okHttpClient.newCall(req).execute()
                val code = resp.code
                resp.close()
                code
            }
        }

        val results = deferreds.awaitAll()
        threadPool.shutdown()

        results.forEach { assertEquals(200, it) }
        assertEquals("Refresh call count must be exactly 1", 1, refreshCallCount.get())
        assertEquals(newAccessToken, encryptedStorage.getAccessToken())
        assertEquals(newRefreshToken, encryptedStorage.getRefreshToken())
        assertEquals("real-user-1", encryptedStorage.getUserId())
        assertEquals("Speedcuber", encryptedStorage.getDisplayName())
    }

    @Test
    fun `multiple sequential concurrency waves - refresh occurs exactly once per wave`() = runBlocking {
        val concurrencyPerWave = 30
        val fakeTokenStorage = MemoryTokenStorage()
        fakeTokenStorage.saveAuthSession(
            accessToken = "token-v1",
            refreshToken = "refresh-v1",
            userId = "user-multi",
            userEmail = "multi@example.com",
            userRole = "user"
        )

        val refreshCalls = AtomicInteger(0)
        var currentServerValidToken = "token-v1"

        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val authHeader = request.getHeader("Authorization")

                return when {
                    path.startsWith("/v1/auth/refresh") -> {
                        val count = refreshCalls.incrementAndGet()
                        val nextAccess = "token-v${count + 1}"
                        val nextRefresh = "refresh-v${count + 1}"
                        currentServerValidToken = nextAccess
                        Thread.sleep(50)
                        val body = """
                            {
                                "access_token": "$nextAccess",
                                "refresh_token": "$nextRefresh",
                                "token_type": "Bearer",
                                "expires_in": 900,
                                "user": {
                                    "id": "user-multi",
                                    "email": "multi@example.com",
                                    "user_role": "user",
                                    "email_verified": true
                                }
                            }
                        """.trimIndent()
                        MockResponse().setResponseCode(200).setBody(body)
                    }
                    path.startsWith("/v1/sync") -> {
                        if (authHeader == "Bearer $currentServerValidToken") {
                            MockResponse().setResponseCode(200).setBody("""{"status":"synced"}""")
                        } else {
                            MockResponse().setResponseCode(401).setBody("""{"error":{"code":"unauthorized"}}""")
                        }
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val authenticator = TokenAuthenticator(
            tokenStorage = fakeTokenStorage,
            baseUrl = mockWebServer.url("/").toString()
        )
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(fakeTokenStorage))
            .authenticator(authenticator)
            .build()

        val threadPool = Executors.newFixedThreadPool(concurrencyPerWave)
        val customDispatcher = threadPool.asCoroutineDispatcher()

        // --- Wave 1: Invalidate initial token, simulate 30 concurrent calls ---
        currentServerValidToken = "invalidated-initial"
        val latch1 = CountDownLatch(concurrencyPerWave)
        val wave1 = (1..concurrencyPerWave).map { i ->
            async(customDispatcher) {
                latch1.countDown()
                latch1.await(5, TimeUnit.SECONDS)
                val resp = okHttpClient.newCall(Request.Builder().url(mockWebServer.url("/v1/sync?w=1&i=$i")).get().build()).execute()
                val code = resp.code
                resp.close()
                code
            }
        }.awaitAll()

        wave1.forEach { assertEquals(200, it) }
        assertEquals(1, refreshCalls.get())
        assertEquals("token-v2", fakeTokenStorage.getAccessToken())
        assertEquals("refresh-v2", fakeTokenStorage.getRefreshToken())

        // --- Wave 2: Token expires again, simulate 30 concurrent calls ---
        currentServerValidToken = "invalidated-v2"
        val latch2 = CountDownLatch(concurrencyPerWave)
        val wave2 = (1..concurrencyPerWave).map { i ->
            async(customDispatcher) {
                latch2.countDown()
                latch2.await(5, TimeUnit.SECONDS)
                val resp = okHttpClient.newCall(Request.Builder().url(mockWebServer.url("/v1/sync?w=2&i=$i")).get().build()).execute()
                val code = resp.code
                resp.close()
                code
            }
        }.awaitAll()

        wave2.forEach { assertEquals(200, it) }
        assertEquals(2, refreshCalls.get())
        assertEquals("token-v3", fakeTokenStorage.getAccessToken())
        assertEquals("refresh-v3", fakeTokenStorage.getRefreshToken())

        threadPool.shutdown()
    }

    @Test
    fun `refresh failure on HTTP 401 triggers onSessionExpired and clears credentials`() {
        val sessionExpired = AtomicBoolean(false)
        val fakeTokenStorage = MemoryTokenStorage()
        fakeTokenStorage.saveAuthSession("expired-tok", "bad-refresh-tok", "u1", "u1@test.com", "user")

        mockWebServer.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":"unauthorized"}}"""))
        mockWebServer.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":"invalid_token"}}"""))

        val authenticator = TokenAuthenticator(
            tokenStorage = fakeTokenStorage,
            baseUrl = mockWebServer.url("/").toString(),
            sessionExpirationListener = { sessionExpired.set(true) }
        )
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(fakeTokenStorage))
            .authenticator(authenticator)
            .build()

        val resp = client.newCall(Request.Builder().url(mockWebServer.url("/v1/data")).get().build()).execute()
        resp.close()

        assertEquals(401, resp.code)
        assertTrue("Session expiration callback must be triggered", sessionExpired.get())
        assertNull("Access token must be purged", fakeTokenStorage.getAccessToken())
        assertNull("Refresh token must be purged", fakeTokenStorage.getRefreshToken())
        assertNull("User ID must be purged", fakeTokenStorage.getUserId())
    }

    @Test
    fun `refresh failure on HTTP 409 token family reuse triggers onSessionExpired and clears credentials`() {
        val sessionExpired = AtomicBoolean(false)
        val fakeTokenStorage = MemoryTokenStorage()
        fakeTokenStorage.saveAuthSession("expired-tok", "reused-refresh-tok", "u1", "u1@test.com", "user")

        mockWebServer.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":"unauthorized"}}"""))
        mockWebServer.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":{"code":"refresh_token_reused","message":"Token family invalidated"}}"""))

        val authenticator = TokenAuthenticator(
            tokenStorage = fakeTokenStorage,
            baseUrl = mockWebServer.url("/").toString(),
            sessionExpirationListener = { sessionExpired.set(true) }
        )
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(fakeTokenStorage))
            .authenticator(authenticator)
            .build()

        val resp = client.newCall(Request.Builder().url(mockWebServer.url("/v1/data")).get().build()).execute()
        resp.close()

        assertEquals(401, resp.code)
        assertTrue("Session expiration callback must be triggered on 409", sessionExpired.get())
        assertNull(fakeTokenStorage.getAccessToken())
        assertNull(fakeTokenStorage.getRefreshToken())
    }

    @Test
    fun `missing refresh token triggers onSessionExpired and aborts refresh attempt`() {
        val sessionExpired = AtomicBoolean(false)
        val fakeTokenStorage = MemoryTokenStorage()
        // Access token exists in memory, but refresh token is missing
        fakeTokenStorage.setAccessToken("expired-tok-no-refresh")

        mockWebServer.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":"unauthorized"}}"""))

        val authenticator = TokenAuthenticator(
            tokenStorage = fakeTokenStorage,
            baseUrl = mockWebServer.url("/").toString(),
            sessionExpirationListener = { sessionExpired.set(true) }
        )
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(fakeTokenStorage))
            .authenticator(authenticator)
            .build()

        val resp = client.newCall(Request.Builder().url(mockWebServer.url("/v1/data")).get().build()).execute()
        resp.close()

        assertEquals(401, resp.code)
        assertEquals(1, mockWebServer.requestCount) // Only 1 request, no /v1/auth/refresh attempted
        assertTrue(sessionExpired.get())
        assertNull(fakeTokenStorage.getAccessToken())
    }

    @Test
    fun `network error during refresh preserves credentials and does NOT trigger onSessionExpired`() {
        val sessionExpired = AtomicBoolean(false)
        val fakeTokenStorage = MemoryTokenStorage()
        fakeTokenStorage.saveAuthSession("expired-tok", "valid-refresh-tok", "u1", "u1@test.com", "user")

        // First call to /v1/data -> 401
        mockWebServer.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":"unauthorized"}}"""))
        // Refresh call -> network disconnect (simulates socket dropped / no internet)
        mockWebServer.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val authenticator = TokenAuthenticator(
            tokenStorage = fakeTokenStorage,
            baseUrl = mockWebServer.url("/").toString(),
            sessionExpirationListener = { sessionExpired.set(true) }
        )
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(fakeTokenStorage))
            .authenticator(authenticator)
            .build()

        val resp = client.newCall(Request.Builder().url(mockWebServer.url("/v1/data")).get().build()).execute()
        resp.close()

        assertEquals(401, resp.code)
        // Must NOT trigger session expired on network error
        assertFalse("Session expiration must NOT trigger on network connectivity failure", sessionExpired.get())
        // Storage must be preserved so user can resume when connection recovers
        assertEquals("valid-refresh-tok", fakeTokenStorage.getRefreshToken())
    }

    @Test
    fun `retry loop guard stops after MAX_RETRIES to prevent infinite loop on persistent 401`() {
        val fakeTokenStorage = MemoryTokenStorage()
        fakeTokenStorage.saveAuthSession("token-loop", "refresh-loop", "u1", "u1@test.com", "user")

        // Dispatcher that always returns 401 for both data and refresh
        var refreshCount = 0
        var dataCount = 0
        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when {
                    request.path?.startsWith("/v1/auth/refresh") == true -> {
                        refreshCount++
                        // Return fresh token on refresh, but server still 401s on data
                        MockResponse().setResponseCode(200).setBody("""
                            {
                                "access_token": "token-loop-$refreshCount",
                                "refresh_token": "refresh-loop",
                                "token_type": "Bearer",
                                "expires_in": 900,
                                "user": {"id":"u1","email":"u@t.com","user_role":"user","email_verified":true}
                            }
                        """.trimIndent())
                    }
                    else -> {
                        dataCount++
                        MockResponse().setResponseCode(401).setBody("""{"error":{"code":"persistent_unauthorized"}}""")
                    }
                }
            }
        }

        val authenticator = TokenAuthenticator(
            tokenStorage = fakeTokenStorage,
            baseUrl = mockWebServer.url("/").toString()
        )
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(fakeTokenStorage))
            .authenticator(authenticator)
            .build()

        val resp = client.newCall(Request.Builder().url(mockWebServer.url("/v1/data")).get().build()).execute()
        resp.close()

        assertEquals(401, resp.code)
        // Should cap out when responseCount reaches MAX_RETRIES (3 total attempts: 1 original + 2 retries)
        assertEquals("Data requests must be bounded by MAX_RETRIES", 3, dataCount)
        assertEquals("Refresh calls must equal retries", 2, refreshCount)
    }

    private class MemoryTokenStorage : TokenStorage {
        private var accessToken: String? = null
        private var refreshToken: String? = null
        private var userId: String? = null
        private var userEmail: String? = null
        private var userRole: String? = null
        private var emailVerified: Boolean = false
        private var displayName: String? = null
        private var deviceId: String = "stress-device-uuid"

        private val _flow = MutableStateFlow<String?>(null)
        override val accessTokenFlow: StateFlow<String?> = _flow

        override fun getAccessToken(): String? = synchronized(this) { accessToken }
        override fun setAccessToken(token: String?) = synchronized(this) {
            accessToken = token
            _flow.value = token
        }
        override fun getRefreshToken(): String? = synchronized(this) { refreshToken }
        override fun setRefreshToken(token: String?) = synchronized(this) { refreshToken = token }
        override fun getUserId(): String? = synchronized(this) { userId }
        override fun getUserEmail(): String? = synchronized(this) { userEmail }
        override fun getUserRole(): String? = synchronized(this) { userRole }
        override fun isUserEmailVerified(): Boolean = synchronized(this) { emailVerified }
        override fun getDisplayName(): String? = synchronized(this) { displayName }
        override fun getCachedUser(): User? = synchronized(this) {
            val id = userId ?: return null
            val email = userEmail ?: return null
            User(id = id, email = email, displayName = displayName, emailVerified = emailVerified)
        }
        override fun saveAuthSession(
            accessToken: String,
            refreshToken: String,
            userId: String,
            userEmail: String,
            userRole: String,
            emailVerified: Boolean,
            displayName: String?
        ) = synchronized(this) {
            setAccessToken(accessToken)
            this.refreshToken = refreshToken
            this.userId = userId
            this.userEmail = userEmail
            this.userRole = userRole
            this.emailVerified = emailVerified
            this.displayName = displayName
        }
        override fun saveUser(user: User) = synchronized(this) {
            this.userId = user.id
            this.userEmail = user.email
            this.userRole = user.userRole.name.lowercase()
            this.emailVerified = user.emailVerified
            this.displayName = user.displayName
        }
        override fun getDeviceId(): String = synchronized(this) { deviceId }
        override fun clearAuthData() = synchronized(this) {
            setAccessToken(null)
            refreshToken = null
            userId = null
            userEmail = null
            userRole = null
            displayName = null
        }
        override fun clearAll() = synchronized(this) {
            clearAuthData()
        }
    }
}
