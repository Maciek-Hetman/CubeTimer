package com.maciekhetman.cubetimer.data.remote

import com.maciekhetman.cubetimer.data.auth.TokenStorage
import com.maciekhetman.cubetimer.model.AuthException
import com.maciekhetman.cubetimer.model.User
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Adversarial contract edge case tests against [MockWebServer] for the shared response handling in
 * [CubeSyncApiClientImpl], exercised through `GET /v1/me`:
 * - 401, 403, 500, 502 HTML responses
 * - Corrupted and empty JSON responses
 */
class ApiClientHttpEdgeCasesMockWebServerTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var apiClient: CubeSyncApiClient
    private lateinit var fakeTokenStorage: FakeTokenStorage
    private val json: Json = NetworkModule.json

    @Before
    fun setup() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        fakeTokenStorage = FakeTokenStorage(accessToken = "secret-jwt")
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
            apiClient.getCurrentUser()
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
                .setBody("""{"error":{"code":"forbidden","message":"Insufficient permissions"}}""")
        )

        try {
            apiClient.getCurrentUser()
            fail("Expected AuthException.Forbidden was not thrown")
        } catch (e: AuthException.Forbidden) {
            assertEquals("Insufficient permissions", e.message)
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
            apiClient.getCurrentUser()
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
            apiClient.getCurrentUser()
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
            apiClient.getCurrentUser()
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
                .setBody("""{"id": "u-1", "email": ,,,}""")
        )

        try {
            apiClient.getCurrentUser()
            fail("Expected AuthException.SerializationError was not thrown")
        } catch (e: AuthException.SerializationError) {
            assertTrue(e.message?.contains("Failed to deserialize") == true)
        }
    }

    private class FakeTokenStorage(
        private var accessToken: String = "test-token"
    ) : TokenStorage {
        override val accessTokenFlow = MutableStateFlow<String?>(accessToken)
        override fun getAccessToken(): String? = accessToken
        override fun setAccessToken(token: String?) { accessToken = token ?: "" }
        override fun getRefreshToken(): String? = "test-refresh"
        override fun setRefreshToken(token: String?) {}
        override fun getUserId(): String? = "user-1"
        override fun getUserEmail(): String? = "user@example.com"
        override fun getUserRole(): String? = "user"
        override fun isUserEmailVerified(): Boolean = true
        override fun getDisplayName(): String? = "User"
        override fun saveAuthSession(accessToken: String, refreshToken: String, userId: String, userEmail: String, userRole: String, emailVerified: Boolean, displayName: String?) {}
        override fun saveUser(user: User) {}
        override fun clearAuthData() {}
        override fun clearAll() {}
        override fun getCachedUser(): User? = null
        override fun getDeviceId(): String = "device-1"
    }
}
