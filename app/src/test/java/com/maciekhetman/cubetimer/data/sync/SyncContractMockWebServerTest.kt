package com.maciekhetman.cubetimer.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.data.auth.AuthManager
import com.maciekhetman.cubetimer.data.auth.TokenStorage
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.local.entity.SessionEntity
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import com.maciekhetman.cubetimer.data.local.entity.SyncOutboxEntity
import com.maciekhetman.cubetimer.data.local.mapper.toSyncPayload
import com.maciekhetman.cubetimer.data.remote.AuthInterceptor
import com.maciekhetman.cubetimer.data.remote.CubeSyncApiClient
import com.maciekhetman.cubetimer.data.remote.CubeSyncAuthApiService
import com.maciekhetman.cubetimer.data.remote.NetworkModule
import com.maciekhetman.cubetimer.data.remote.dto.SessionSyncPayload
import com.maciekhetman.cubetimer.data.remote.dto.SolveSyncPayload
import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.model.User
import com.maciekhetman.cubetimer.model.UserRole
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class SyncContractMockWebServerTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var database: CubeDatabase
    private lateinit var fakeTokenStorage: FakeTokenStorage
    private lateinit var fakeAuthManager: FakeAuthManager
    private lateinit var apiClient: CubeSyncApiClient
    private lateinit var syncEngine: SyncEngineImpl
    private val json: Json = NetworkModule.json

    private val testUser = User(
        id = "user-mws-1",
        email = "mws@example.com",
        userRole = UserRole.USER,
        emailVerified = true,
        displayName = "MWS User"
    )

    @Before
    fun setup() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, CubeDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        fakeTokenStorage = FakeTokenStorage(accessToken = "test-bearer-token", deviceId = "device-mws-999")
        fakeAuthManager = FakeAuthManager(AuthState.Authenticated(testUser))

        val authInterceptor = AuthInterceptor(fakeTokenStorage)
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .build()

        val apiService: CubeSyncAuthApiService = NetworkModule.provideAuthApiService(
            baseUrl = mockWebServer.url("/").toString(),
            okHttpClient = okHttpClient,
            json = json
        )
        apiClient = NetworkModule.provideCubeSyncApiClient(apiService)

        syncEngine = SyncEngineImpl(
            apiClient = apiClient,
            tokenStorage = fakeTokenStorage,
            database = database,
            authManager = fakeAuthManager,
            conflictResolver = ConflictResolverImpl(database),
            json = json
        )
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        database.close()
    }

    @Test
    fun postSync_sendsCorrectHeadersAndRequestBody() = runTest {
        val syncResponseBody = """
        {
            "outcomes": [],
            "changes": [],
            "next_cursor": 42,
            "has_more": false
        }
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(syncResponseBody)
        )

        val result = syncEngine.sync(testUser.id)
        assertTrue(result is SyncResult.Success)

        val recordedRequest = mockWebServer.takeRequest()
        assertEquals("/v1/sync", recordedRequest.path)
        assertEquals("POST", recordedRequest.method)
        assertEquals("Bearer test-bearer-token", recordedRequest.getHeader("Authorization"))
        assertEquals("device-mws-999", recordedRequest.getHeader("X-Device-Id"))
        assertEquals("2", recordedRequest.getHeader("X-Sync-Protocol"))
        assertTrue(recordedRequest.getHeader("Content-Type")?.startsWith("application/json") == true)

        val body = recordedRequest.body.readUtf8()
        assertTrue(body.contains("\"cursor\":0"))
        assertTrue(body.contains("\"device\":{\"id\":\"device-mws-999\""))
    }

    @Test
    fun postSync_multiPagePagination_continuesWhileHasMoreIsTrue() = runTest {
        // Page 1: has_more = true, next_cursor = 10
        val page1Body = """
        {
            "outcomes": [],
            "changes": [
                {
                    "cursor": 10,
                    "entity": "session",
                    "entity_id": "sess-page-1",
                    "operation": "upsert",
                    "version": 1,
                    "data": {
                        "id": "sess-page-1",
                        "name": "Page 1 Session",
                        "event": "3x3",
                        "kind": "automatic",
                        "started_at": "2026-08-30T08:00:00Z",
                        "version": 1
                    }
                }
            ],
            "next_cursor": 10,
            "has_more": true
        }
        """.trimIndent()

        // Page 2: has_more = false, next_cursor = 20
        val page2Body = """
        {
            "outcomes": [],
            "changes": [
                {
                    "cursor": 20,
                    "entity": "solve",
                    "entity_id": "solve-page-2",
                    "operation": "upsert",
                    "version": 1,
                    "data": {
                        "id": "solve-page-2",
                        "session_id": "sess-page-1",
                        "duration_ms": 13500,
                        "penalty": "none",
                        "solved_at": "2026-08-30T08:05:00Z",
                        "scramble": "R U R' U'",
                        "event": "3x3",
                        "version": 1
                    }
                }
            ],
            "next_cursor": 20,
            "has_more": false
        }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(page1Body))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(page2Body))

        val result = syncEngine.sync(testUser.id)
        assertTrue(result is SyncResult.Success)
        val success = result as SyncResult.Success
        assertEquals(2, success.changesApplied)

        // Verify two HTTP requests were sent
        assertEquals(2, mockWebServer.requestCount)
        val req1 = mockWebServer.takeRequest()
        val req2 = mockWebServer.takeRequest()
        assertTrue(req1.body.readUtf8().contains("\"cursor\":0"))
        assertTrue(req2.body.readUtf8().contains("\"cursor\":10"))

        // Verify entities inserted into Room
        val sess = database.sessionDao().getSessionById("sess-page-1")
        assertNotNull(sess)
        assertEquals("Page 1 Session", sess?.name)

        val solve = database.solveDao().getSolveById("solve-page-2")
        assertNotNull(solve)
        assertEquals(13500L, solve?.durationMs)

        // Verify final watermark cursor
        val metadata = database.syncMetadataDao().getMetadata(testUser.id)
        assertEquals(20L, metadata?.cursor)
    }

    @Test
    fun postSync_whenCursorExpired409_recoversViaSnapshotAndResumesSync() = runTest {
        // 1. Initial sync returns 409 cursor_expired
        val error409Body = """
        {
            "error": {
                "code": "cursor_expired",
                "message": "sync cursor has expired; resync required"
            }
        }
        """.trimIndent()

        // 2. Snapshot endpoint returns materialized state
        val snapshotBody = """
        {
            "sessions": [
                {
                    "id": "snap-sess-1",
                    "name": "Bootstrap Session",
                    "event": "3x3",
                    "kind": "manual",
                    "started_at": "2026-08-30T07:00:00Z",
                    "version": 5
                }
            ],
            "solves": [
                {
                    "id": "snap-solve-1",
                    "session_id": "snap-sess-1",
                    "duration_ms": 10500,
                    "penalty": "none",
                    "solved_at": "2026-08-30T07:15:00Z",
                    "scramble": "R U R' U'",
                    "event": "3x3",
                    "version": 5
                }
            ],
            "cursor": 300,
            "has_more": false
        }
        """.trimIndent()

        // 3. Resumed sync with cursor 300 returns 200 OK
        val resumedSyncBody = """
        {
            "outcomes": [],
            "changes": [],
            "next_cursor": 300,
            "has_more": false
        }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(409).setBody(error409Body))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(snapshotBody))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(resumedSyncBody))

        val result = syncEngine.sync(testUser.id)
        assertTrue(result is SyncResult.Success)

        assertEquals(3, mockWebServer.requestCount)
        val req1 = mockWebServer.takeRequest() // POST /v1/sync
        assertEquals("/v1/sync", req1.path)

        val req2 = mockWebServer.takeRequest() // POST /v1/snapshot
        assertEquals("/v1/snapshot", req2.path)

        val req3 = mockWebServer.takeRequest() // POST /v1/sync with cursor 300
        assertEquals("/v1/sync", req3.path)
        assertTrue(req3.body.readUtf8().contains("\"cursor\":300"))

        // Verify snapshot data persisted in Room
        val sess = database.sessionDao().getSessionById("snap-sess-1")
        assertNotNull(sess)
        assertEquals("Bootstrap Session", sess?.name)

        val solve = database.solveDao().getSolveById("snap-solve-1")
        assertNotNull(solve)
        assertEquals(10500L, solve?.durationMs)

        val metadata = database.syncMetadataDao().getMetadata(testUser.id)
        assertEquals(300L, metadata?.cursor)
    }

    private class FakeTokenStorage(
        private var accessToken: String = "test-token",
        private val deviceId: String = "test-device"
    ) : TokenStorage {
        override val accessTokenFlow = MutableStateFlow<String?>(accessToken)
        override fun getAccessToken(): String? = accessToken
        override fun setAccessToken(token: String?) { accessToken = token ?: "" }
        override fun getRefreshToken(): String? = "test-refresh-token"
        override fun setRefreshToken(token: String?) {}
        override fun getUserId(): String? = "user-mws-1"
        override fun getUserEmail(): String? = "mws@example.com"
        override fun getUserRole(): String? = "user"
        override fun isUserEmailVerified(): Boolean = true
        override fun getDisplayName(): String? = "MWS User"
        override fun saveAuthSession(accessToken: String, refreshToken: String, userId: String, userEmail: String, userRole: String, emailVerified: Boolean, displayName: String?) {}
        override fun saveUser(user: User) {}
        override fun clearAuthData() {}
        override fun clearAll() {}
        override fun getCachedUser(): User? = null
        override fun getDeviceId(): String = deviceId
    }

    private class FakeAuthManager(initialState: AuthState) : AuthManager {
        val authStateFlow = MutableStateFlow(initialState)
        override val authState: StateFlow<AuthState> = authStateFlow
        override val currentUser: User? get() = (authState.value as? AuthState.Authenticated)?.user

        override suspend fun initialize() {}
        override suspend fun register(email: String, password: String) = throw NotImplementedError()
        override suspend fun login(email: String, password: String) = throw NotImplementedError()
        override suspend fun loginWithGoogle(idToken: String) = throw NotImplementedError()
        override suspend fun verifyEmail(token: String) = throw NotImplementedError()
        override suspend fun resendVerificationEmail(email: String) = throw NotImplementedError()
        override suspend fun requestPasswordReset(email: String) = throw NotImplementedError()
        override suspend fun resetPassword(token: String, newPassword: String) = throw NotImplementedError()
        override suspend fun refreshSession() = throw NotImplementedError()
        override suspend fun logout() = throw NotImplementedError()
        override suspend fun adoptGuestData(userId: String) {}
    }
}
