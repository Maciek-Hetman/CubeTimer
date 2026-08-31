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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class AdminContractMockWebServerTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var apiClient: CubeSyncApiClient
    private lateinit var fakeTokenStorage: FakeTokenStorage
    private val json: Json = NetworkModule.json

    @Before
    fun setup() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        fakeTokenStorage = FakeTokenStorage(accessToken = "admin-bearer-token")
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

    @Test
    fun testGetAdminOverviewParsesJsonCorrectly() = runTest {
        val jsonBody = """
{
  "total_users": 150,
  "verified_users": 120,
  "new_users_24h": 8,
  "new_users_7d": 25,
  "new_users_30d": 55,
  "active_users_24h": 50,
  "active_users_7d": 110,
  "active_users_30d": 140,
  "total_devices": 180,
  "total_sessions": 450,
  "total_solves": 9000
}
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(jsonBody)
        )

        val overview = apiClient.getAdminOverview()

        val recordedRequest = mockWebServer.takeRequest()
        assertEquals("/v1/admin/stats/overview", recordedRequest.path)
        assertEquals("GET", recordedRequest.method)
        assertEquals("Bearer admin-bearer-token", recordedRequest.getHeader("Authorization"))

        assertEquals(150, overview.totalUsers)
        assertEquals(120, overview.verifiedUsers)
        assertEquals(180, overview.totalDevices)
        assertEquals(450, overview.totalSessions)
        assertEquals(9000, overview.totalSolves)
        assertEquals(50, overview.activeUsers24h)
        assertEquals(110, overview.activeUsers7d)
        assertEquals(140, overview.activeUsers30d)
        assertEquals(8, overview.newUsers24h)
        assertEquals(25, overview.newUsers7d)
        assertEquals(55, overview.newUsers30d)
    }

    @Test
    fun testGetAdminRequestStatsParsesPointsArray() = runTest {
        val jsonBody = """
{
  "from": "2026-08-29T10:00:00Z",
  "to": "2026-08-30T10:00:00Z",
  "interval": "hour",
  "points": [
    {
      "bucket": "2026-08-30T10:00:00Z",
      "request_count": 120,
      "status_2xx": 115,
      "status_3xx": 0,
      "status_4xx": 3,
      "status_5xx": 2,
      "average_duration_ms": 42.5,
      "max_duration_ms": 180
    }
  ]
}
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(jsonBody)
        )

        val stats = apiClient.getAdminRequestStats(from = "2026-08-29T10:00:00Z", to = "2026-08-30T10:00:00Z", interval = "hour")

        val recordedRequest = mockWebServer.takeRequest()
        assertTrue(recordedRequest.path!!.startsWith("/v1/admin/stats/requests"))

        assertEquals("hour", stats.interval)
        assertEquals(1, stats.points.size)
        assertEquals(120, stats.points[0].requestCount)
        assertEquals(115, stats.points[0].status2xx)
        assertEquals(42.5, stats.points[0].averageDurationMs, 0.01)
    }

    @Test
    fun testGetAdminRequestTypeStatsParsesTypesArray() = runTest {
        val jsonBody = """
{
  "from": "2026-08-23T10:00:00Z",
  "to": "2026-08-30T10:00:00Z",
  "interval": "hour",
  "types": [
    { "type": "sync", "request_count": 1500 },
    { "type": "auth", "request_count": 600 }
  ]
}
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(jsonBody)
        )

        val typeStats = apiClient.getAdminRequestTypeStats(from = "2026-08-23T10:00:00Z", to = "2026-08-30T10:00:00Z", interval = "hour")

        val recordedRequest = mockWebServer.takeRequest()
        assertTrue(recordedRequest.path!!.startsWith("/v1/admin/stats/request-types"))

        assertEquals(2, typeStats.types.size)
        assertEquals("sync", typeStats.types[0].type)
        assertEquals(1500, typeStats.types[0].requestCount)
    }

    @Test
    fun testGetAdminErrorLogsParsesPagination() = runTest {
        val jsonBody = """
{
  "errors": [
    {
      "id": 101,
      "created_at": "2026-08-30T10:00:00Z",
      "method": "POST",
      "route": "/v1/sync",
      "status": 409,
      "code": "cursor_expired",
      "message": "Sync cursor expired",
      "user_id": "usr_999"
    }
  ],
  "next_cursor": "cur_next_999"
}
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(jsonBody)
        )

        val errorLogs = apiClient.getAdminErrorLogs(before = "cur_initial")

        val recordedRequest = mockWebServer.takeRequest()
        assertTrue(recordedRequest.path!!.startsWith("/v1/admin/stats/errors"))

        assertEquals(1, errorLogs.errors.size)
        assertEquals(101, errorLogs.errors[0].id)
        assertEquals("POST", errorLogs.errors[0].method)
        assertEquals("/v1/sync", errorLogs.errors[0].route)
        assertEquals(409, errorLogs.errors[0].status)
        assertEquals("cursor_expired", errorLogs.errors[0].code)
        assertEquals("usr_999", errorLogs.errors[0].userId)
        assertEquals("cur_next_999", errorLogs.nextCursor)
    }

    @Test
    fun testForbiddenResponseThrowsAuthExceptionForbidden() = runTest {
        val errorJson = """
{
  "error": {
    "code": "forbidden",
    "message": "Admin privileges required"
  }
}
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("Content-Type", "application/json")
                .setBody(errorJson)
        )

        try {
            apiClient.getAdminOverview()
            fail("Expected AuthException.Forbidden was not thrown")
        } catch (e: AuthException.Forbidden) {
            assertEquals("Admin privileges required", e.message)
        }
    }

    private class FakeTokenStorage(
        private var accessToken: String = "test-token"
    ) : TokenStorage {
        override val accessTokenFlow = MutableStateFlow<String?>(accessToken)
        override fun getAccessToken(): String? = accessToken
        override fun setAccessToken(token: String?) { accessToken = token ?: "" }
        override fun getRefreshToken(): String? = "test-refresh-token"
        override fun setRefreshToken(token: String?) {}
        override fun getUserId(): String? = "admin-user-1"
        override fun getUserEmail(): String? = "admin@example.com"
        override fun getUserRole(): String? = "admin"
        override fun isUserEmailVerified(): Boolean = true
        override fun getDisplayName(): String? = "Admin User"
        override fun saveAuthSession(accessToken: String, refreshToken: String, userId: String, userEmail: String, userRole: String, emailVerified: Boolean, displayName: String?) {}
        override fun saveUser(user: User) {}
        override fun clearAuthData() {}
        override fun clearAll() {}
        override fun getCachedUser(): User? = null
        override fun getDeviceId(): String = "test-device"
    }
}
