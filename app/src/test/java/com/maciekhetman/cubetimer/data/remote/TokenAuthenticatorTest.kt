package com.maciekhetman.cubetimer.data.remote

import com.maciekhetman.cubetimer.data.auth.SessionExpirationListener
import com.maciekhetman.cubetimer.data.auth.TokenStorage
import com.maciekhetman.cubetimer.model.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
class TokenAuthenticatorTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var fakeTokenStorage: FakeTokenStorage
    private lateinit var okHttpClient: OkHttpClient
    private val sessionExpiredCalled = AtomicBoolean(false)

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        val baseUrl = mockWebServer.url("/").toString()
        fakeTokenStorage = FakeTokenStorage()
        sessionExpiredCalled.set(false)

        val expirationListener = SessionExpirationListener {
            sessionExpiredCalled.set(true)
        }

        val authenticator = TokenAuthenticator(
            tokenStorage = fakeTokenStorage,
            baseUrl = baseUrl,
            sessionExpirationListener = expirationListener
        )

        okHttpClient = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(fakeTokenStorage))
            .authenticator(authenticator)
            .build()
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `intercepts 401, executes refresh, and retries request with new token`() {
        fakeTokenStorage.storedAccessToken = "expired-token"
        fakeTokenStorage.storedRefreshToken = "valid-refresh-token"

        val refreshResponseBody = """
            {
                "access_token": "fresh-access-token",
                "refresh_token": "rotated-refresh-token",
                "token_type": "Bearer",
                "expires_in": 900,
                "user": {
                    "id": "user-42",
                    "email": "cuber@example.com",
                    "user_role": "user",
                    "email_verified": true
                }
            }
        """.trimIndent()

        // 1. First call to /v1/sync returns 401
        mockWebServer.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":"unauthorized","message":"expired"}}"""))
        // 2. TokenAuthenticator calls POST /v1/auth/refresh -> returns 200
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(refreshResponseBody))
        // 3. Retried call to /v1/sync returns 200
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))

        val request = Request.Builder()
            .url(mockWebServer.url("/v1/sync"))
            .get()
            .build()

        val response = okHttpClient.newCall(request).execute()
        val body = response.body?.string()
        response.close()

        assertEquals(200, response.code)
        assertEquals("""{"status":"ok"}""", body)

        // Verify tokenStorage was updated
        assertEquals("fresh-access-token", fakeTokenStorage.storedAccessToken)
        assertEquals("rotated-refresh-token", fakeTokenStorage.storedRefreshToken)

        // Verify request sequence
        val firstReq = mockWebServer.takeRequest()
        assertEquals("/v1/sync", firstReq.path)
        assertEquals("Bearer expired-token", firstReq.getHeader("Authorization"))

        val refreshReq = mockWebServer.takeRequest()
        assertEquals("/v1/auth/refresh", refreshReq.path)
        assertTrue(refreshReq.body.readUtf8().contains("valid-refresh-token"))

        val retryReq = mockWebServer.takeRequest()
        assertEquals("/v1/sync", retryReq.path)
        assertEquals("Bearer fresh-access-token", retryReq.getHeader("Authorization"))
    }

    @Test
    fun `concurrent 401 requests trigger only 1 refresh call due to mutex and double-checked locking`() = runBlocking {
        fakeTokenStorage.storedAccessToken = "expired-token"
        fakeTokenStorage.storedRefreshToken = "valid-refresh-token"

        val refreshResponseBody = """
            {
                "access_token": "fresh-access-token-concurrent",
                "refresh_token": "rotated-refresh-token-concurrent",
                "token_type": "Bearer",
                "expires_in": 900,
                "user": {
                    "id": "user-42",
                    "email": "cuber@example.com",
                    "user_role": "user",
                    "email_verified": true
                }
            }
        """.trimIndent()

        val refreshCalls = java.util.concurrent.atomic.AtomicInteger(0)
        mockWebServer.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val authHeader = request.getHeader("Authorization")
                return when {
                    path.startsWith("/v1/auth/refresh") -> {
                        refreshCalls.incrementAndGet()
                        MockResponse().setResponseCode(200).setBody(refreshResponseBody)
                    }
                    path.startsWith("/v1/data") -> {
                        if (authHeader == "Bearer fresh-access-token-concurrent") {
                            MockResponse().setResponseCode(200).setBody("""{"status":"ok"}""")
                        } else {
                            MockResponse().setResponseCode(401).setBody("""{"error":{"code":"unauthorized"}}""")
                        }
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val deferreds = (1..4).map {
            async(Dispatchers.IO) {
                val req = Request.Builder().url(mockWebServer.url("/v1/data")).get().build()
                val resp = okHttpClient.newCall(req).execute()
                val code = resp.code
                resp.close()
                code
            }
        }

        val results = deferreds.awaitAll()
        results.forEach { assertEquals(200, it) }

        assertEquals(1, refreshCalls.get())
        assertEquals("fresh-access-token-concurrent", fakeTokenStorage.storedAccessToken)
        assertEquals("rotated-refresh-token-concurrent", fakeTokenStorage.storedRefreshToken)
    }

    @Test
    fun `clears storage and notifies listener when refresh token fails with 409 reused`() {
        fakeTokenStorage.storedAccessToken = "expired-token"
        fakeTokenStorage.storedRefreshToken = "revoked-refresh-token"

        mockWebServer.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":"unauthorized"}}"""))
        mockWebServer.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":{"code":"refresh_token_reused","message":"Revoked"}}"""))

        val request = Request.Builder()
            .url(mockWebServer.url("/v1/sync"))
            .get()
            .build()

        val response = okHttpClient.newCall(request).execute()
        response.close()

        assertEquals(401, response.code)
        assertNull(fakeTokenStorage.storedAccessToken)
        assertNull(fakeTokenStorage.storedRefreshToken)
        assertTrue(sessionExpiredCalled.get())
    }

    @Test
    fun `skips refresh when 401 occurs on auth endpoint directly`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":"invalid_credentials"}}"""))

        val request = Request.Builder()
            .url(mockWebServer.url("/v1/auth/login"))
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()

        val response = okHttpClient.newCall(request).execute()
        response.close()

        assertEquals(401, response.code)
        assertEquals(1, mockWebServer.requestCount)
        assertFalse(sessionExpiredCalled.get())
    }

    private class FakeTokenStorage : TokenStorage {
        var storedAccessToken: String? = null
        var storedRefreshToken: String? = null
        var storedUserId: String? = null
        var storedUserEmail: String? = null
        var storedUserRole: String? = null
        var storedEmailVerified: Boolean = false
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
        ) {
            setAccessToken(accessToken)
            this.storedRefreshToken = refreshToken
            this.storedUserId = userId
            this.storedUserEmail = userEmail
            this.storedUserRole = userRole
            this.storedEmailVerified = emailVerified
        }
        override fun saveUser(user: User) {}
        override fun getDeviceId(): String = storedDeviceId
        override fun clearAuthData() {
            setAccessToken(null)
            storedRefreshToken = null
            storedUserId = null
            storedUserEmail = null
            storedUserRole = null
        }
        override fun clearAll() {
            clearAuthData()
        }
    }
}
