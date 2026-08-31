package com.maciekhetman.cubetimer.data.remote

import com.maciekhetman.cubetimer.data.auth.TokenStorage
import com.maciekhetman.cubetimer.model.AuthException
import com.maciekhetman.cubetimer.model.User
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Adversarial contract edge case tests against [MockWebServer] covering:
 * - 401, 403, 500, 502 HTML responses
 * - Corrupted and empty JSON responses
 * - Edge case payload deserialization (empty lists, null fields)
 * - Query parameter and Authorization header verification
 */
class AdminContractEdgeCasesMockWebServerTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var apiClient: CubeSyncApiClient
    private lateinit var fakeTokenStorage: FakeAdminTokenStorage
    private val json: Json = NetworkModule.json

    @Before
    fun setup() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        fakeTokenStorage = FakeAdminTokenStorage(accessToken = "admin-secret-jwt")
        val authInterceptor = AuthInterceptor(fakeTokenStorage)
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .build()

        val apiService = NetworkModule.provideAuthApiService(
            baseUrl = mockWebServer.url("/").toString(),
            okHttpClient = okHttpClient,
            json = json
        )

        apiClient = NetworkModule.provideCubeSyncApiClient(apiService)
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    // ---------------------------------------------------------------------------------------------
    // HTTP STATUS CODE EDGE CASES
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `401 Unauthorized throws AuthException Unauthorized with parsed error code`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"code":"token_expired","message":"The access token has expired"}}""")
        )

        try {
            apiClient.getAdminOverview()
            fail("Expected AuthException.Unauthorized was not thrown")
        } catch (e: AuthException.Unauthorized) {
            assertEquals("The access token has expired", e.message)
        }
    }

    @Test
    fun `403 Forbidden throws AuthException Forbidden`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"code":"forbidden","message":"Requires admin privileges"}}""")
        )

        try {
            apiClient.getAdminRequestStats(from = null, to = null, interval = null)
            fail("Expected AuthException.Forbidden was not thrown")
        } catch (e: AuthException.Forbidden) {
            assertEquals("Requires admin privileges", e.message)
        }
    }

    @Test
    fun `500 Internal Server Error with JSON error body maps to AuthException`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"code":"internal_server_error","message":"Database replica unavailable"}}""")
        )

        try {
            apiClient.getAdminErrorLogs()
            fail("Expected AuthException was not thrown")
        } catch (e: AuthException) {
            assertTrue(e.message?.contains("Database replica unavailable") == true)
        }
    }

    @Test
    fun `502 Bad Gateway with HTML error body from reverse proxy is parsed safely without crash`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(502)
                .setHeader("Content-Type", "text/html")
                .setBody("<html><head><title>502 Bad Gateway</title></head><body><center>nginx/1.24.0</center></body></html>")
        )

        try {
            apiClient.getAdminOverview()
            fail("Expected AuthException was not thrown on 502 HTML")
        } catch (e: AuthException) {
            assertTrue("Exception must be caught as AuthException", e is AuthException)
        }
    }

    @Test
    fun `200 OK with empty response body throws AuthException Unknown`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("")
        )

        try {
            apiClient.getAdminOverview()
            fail("Expected AuthException was not thrown on empty body")
        } catch (e: AuthException) {
            assertTrue(e is AuthException.SerializationError || e is AuthException.Unknown)
        }
    }

    @Test
    fun `200 OK with malformed JSON throws AuthException SerializationError`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"total_users": "INVALID_NUMBER_STRING",,,}""")
        )

        try {
            apiClient.getAdminOverview()
            fail("Expected AuthException.SerializationError was not thrown")
        } catch (e: AuthException.SerializationError) {
            assertTrue(e.message?.contains("Failed to deserialize") == true)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // EDGE CASE PAYLOADS & DESERIALIZATION
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `request stats with 0 points array deserializes cleanly`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"from":"2026-08-29T00:00:00Z","to":"2026-08-30T00:00:00Z","interval":"hour","points":[]}""")
        )

        val result = apiClient.getAdminRequestStats(from = "2026-08-29T00:00:00Z", to = "2026-08-30T00:00:00Z", interval = "hour")
        assertEquals(0, result.points.size)
        assertEquals("hour", result.interval)
        assertEquals("2026-08-29T00:00:00Z", result.from)
    }

    @Test
    fun `request type stats with 0 types array deserializes cleanly`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"from":null,"to":null,"interval":"day","types":[]}""")
        )

        val result = apiClient.getAdminRequestTypeStats(from = null, to = null, interval = "day")
        assertEquals(0, result.types.size)
        assertEquals("day", result.interval)
        assertNull(result.from)
    }

    @Test
    fun `error logs with null user_id and null next_cursor deserialize without error`() = runTest {
        val jsonBody = """
{
  "errors": [
    {
      "id": 99,
      "created_at": "2026-08-30T15:30:00Z",
      "user_id": null,
      "method": "GET",
      "route": "/v1/health",
      "status": 503,
      "code": "service_unavailable",
      "message": "Service unhealthy"
    }
  ],
  "next_cursor": null
}
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(jsonBody)
        )

        val result = apiClient.getAdminErrorLogs()
        assertEquals(1, result.errors.size)
        assertNull(result.errors.first().userId)
        assertNull(result.nextCursor)
        assertEquals(503, result.errors.first().status)
    }

    @Test
    fun `query parameters and Bearer auth header are correctly formatted`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"errors":[],"next_cursor":null}""")
        )

        apiClient.getAdminErrorLogs(before = "cursor_abc_123")

        val recorded = mockWebServer.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("Bearer admin-secret-jwt", recorded.getHeader("Authorization"))
        assertTrue(recorded.path!!.contains("/v1/admin/stats/errors?before=cursor_abc_123"))
    }

    private class FakeAdminTokenStorage(
        private var accessToken: String = "test-token"
    ) : TokenStorage {
        override val accessTokenFlow = MutableStateFlow<String?>(accessToken)
        override fun getAccessToken(): String? = accessToken
        override fun setAccessToken(token: String?) { accessToken = token ?: "" }
        override fun getRefreshToken(): String? = "test-refresh"
        override fun setRefreshToken(token: String?) {}
        override fun getUserId(): String? = "admin-1"
        override fun getUserEmail(): String? = "admin@example.com"
        override fun getUserRole(): String? = "admin"
        override fun isUserEmailVerified(): Boolean = true
        override fun getDisplayName(): String? = "Admin"
        override fun saveAuthSession(accessToken: String, refreshToken: String, userId: String, userEmail: String, userRole: String, emailVerified: Boolean, displayName: String?) {}
        override fun saveUser(user: User) {}
        override fun clearAuthData() {}
        override fun clearAll() {}
        override fun getCachedUser(): User? = null
        override fun getDeviceId(): String = "device-1"
    }
}
