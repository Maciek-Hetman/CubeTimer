package com.maciekhetman.cubetimer.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.local.entity.SyncMetadataEntity
import com.maciekhetman.cubetimer.data.remote.AuthInterceptor
import com.maciekhetman.cubetimer.data.remote.NetworkModule
import com.maciekhetman.cubetimer.data.remote.dto.SnapshotResponse
import com.maciekhetman.cubetimer.data.remote.dto.SyncRequest
import com.maciekhetman.cubetimer.data.remote.dto.SyncResponse
import com.maciekhetman.cubetimer.model.AuthException
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Only a 409 that means "cursor expired" may trigger a snapshot bootstrap (a full re-download).
 * Before, every 409 did. Also runs the request-level 4xx handling over real HTTP, so that
 * `ErrorParser`'s exception mapping is part of what is verified.
 */
@RunWith(RobolectricTestRunner::class)
class SyncEngineCursorExpiredRuleTest {

    private lateinit var database: CubeDatabase
    private lateinit var api: ScriptedSyncApiClient
    private lateinit var engine: SyncEngineImpl
    private val json = NetworkModule.json

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = CubeDatabase.createInMemory(context)
        api = ScriptedSyncApiClient()
        engine = SyncEngineImpl(
            apiClient = api,
            tokenStorage = StubTokenStorage(),
            database = database,
            authManager = StubAuthManager(),
            json = json
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun seedCursor(cursor: Long) {
        database.syncMetadataDao().upsert(
            SyncMetadataEntity(ownerId = ROBUSTNESS_OWNER_ID, cursor = cursor, deviceId = "robust-device")
        )
    }

    private suspend fun storedCursor(): Long? = database.syncMetadataDao().getMetadata(ROBUSTNESS_OWNER_ID)?.cursor

    /** First sync request throws [failure]; later ones succeed with cursor 500 (the snapshot's). */
    private fun failFirstSyncWith(failure: Exception) {
        api.syncHandler = { request: SyncRequest ->
            if (api.syncRequests.size == 1) throw failure
            SyncResponse(nextCursor = 500L)
        }
        api.snapshotHandler = { SnapshotResponse(cursor = 500L, hasMore = false) }
    }

    // ------------------------------------------------------------------------------------------
    // 409s that are NOT cursor expiry
    // ------------------------------------------------------------------------------------------

    @Test
    fun a409WithAnotherExplicitErrorCode_doesNotBootstrap_andLeavesCursorAndRowsAlone() = runTest {
        seedCursor(77L)
        database.enqueueSolve("solve-1", order = 1)
        failFirstSyncWith(AuthException.ApiError("version_conflict", "entity changed", 409))

        val result = engine.sync(ROBUSTNESS_OWNER_ID)

        assertTrue("got $result", result is SyncResult.Error)
        assertTrue("no full re-download", api.snapshotRequests.isEmpty())
        assertEquals(1, api.syncRequests.size)
        assertEquals("cursor untouched", 77L, storedCursor())
        val row = database.syncOutboxDao().getMutationById("mut-solve-1")
        assertEquals("not dead either: 409 is never a permanent rejection", "failed", row?.status)
    }

    @Test
    fun otherCodedConflicts_ofEveryFlavour_doNotBootstrap() = runTest {
        for (code in listOf("conflict", "idempotency_key_reused", "stale_write", "Version_Conflict")) {
            api.syncRequests.clear()
            api.snapshotRequests.clear()
            seedCursor(77L)
            failFirstSyncWith(AuthException.ApiError(code, "nope", 409))

            engine.sync(ROBUSTNESS_OWNER_ID)

            assertTrue("409 $code must not bootstrap", api.snapshotRequests.isEmpty())
            assertEquals(77L, storedCursor())
        }
    }

    // ------------------------------------------------------------------------------------------
    // Cursor expiry, in every shape it can arrive
    // ------------------------------------------------------------------------------------------

    @Test
    fun cursorExpiredException_bootstraps() = runTest {
        seedCursor(77L)
        failFirstSyncWith(AuthException.CursorExpired("expired"))

        val result = engine.sync(ROBUSTNESS_OWNER_ID)

        assertTrue("got $result", result is SyncResult.Success)
        assertEquals(1, api.snapshotRequests.size)
        assertEquals(500L, storedCursor())
    }

    @Test
    fun apiError409WithCursorExpiredCode_bootstraps() = runTest {
        seedCursor(77L)
        failFirstSyncWith(AuthException.ApiError("cursor_expired", "expired", 409))

        val result = engine.sync(ROBUSTNESS_OWNER_ID)

        assertTrue("got $result", result is SyncResult.Success)
        assertEquals(1, api.snapshotRequests.size)
        assertEquals(500L, storedCursor())
    }

    @Test
    fun a409WithNoUsableErrorCode_isTakenAsCursorExpiry() = runTest {
        for (code in listOf("unknown_error", "", "  ")) {
            api.syncRequests.clear()
            api.snapshotRequests.clear()
            seedCursor(77L)
            failFirstSyncWith(AuthException.ApiError(code, "HTTP Error 409", 409))

            val result = engine.sync(ROBUSTNESS_OWNER_ID)

            assertTrue("code '$code': got $result", result is SyncResult.Success)
            assertEquals("code '$code'", 1, api.snapshotRequests.size)
        }
    }

    @Test
    fun cursorExpiredRecovery_handsPendingRowsBack_andUploadsThemAfterTheBootstrap() = runTest {
        seedCursor(77L)
        database.enqueueSolve("solve-1", order = 1)
        database.enqueueSolve("solve-2", order = 2)
        api.syncHandler = { request ->
            if (api.syncRequests.size == 1) throw AuthException.CursorExpired("expired")
            acceptAll(request)
        }
        api.snapshotHandler = { SnapshotResponse(cursor = 500L, hasMore = false) }

        val result = engine.sync(ROBUSTNESS_OWNER_ID)

        assertEquals(2, (result as SyncResult.Success).mutationsSynced)
        assertEquals(0, database.syncOutboxDao().countPending(ROBUSTNESS_OWNER_ID))
        assertEquals(listOf("mut-solve-1", "mut-solve-2"), api.syncRequests.last().mutations.map { it.id })
    }

    // ------------------------------------------------------------------------------------------
    // Over real HTTP (MockWebServer + Retrofit + ErrorParser)
    // ------------------------------------------------------------------------------------------

    private inner class HttpFixture {
        val server = MockWebServer()
        val requests = mutableListOf<RecordedRequest>()
        val httpEngine: SyncEngineImpl

        init {
            server.start()
            val okHttp = OkHttpClient.Builder().addInterceptor(AuthInterceptor(StubTokenStorage())).build()
            val service = NetworkModule.provideAuthApiService(server.url("/").toString(), okHttp, json)
            httpEngine = SyncEngineImpl(
                apiClient = NetworkModule.provideCubeSyncApiClient(service),
                tokenStorage = StubTokenStorage(),
                database = database,
                authManager = StubAuthManager(),
                json = json
            )
        }

        fun respond(handler: (RecordedRequest, String) -> MockResponse) {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = request.body.readUtf8()
                    synchronized(requests) { requests += request }
                    return handler(request, body)
                }
            }
        }

        fun shutdown() = server.shutdown()
    }

    private fun errorBody(code: String, message: String) = """{"error":{"code":"$code","message":"$message"}}"""

    private fun acceptedJson(request: SyncRequest, nextCursor: Long): String {
        val outcomes = request.mutations.joinToString(",") {
            """{"mutation_id":"${it.id}","status":"accepted","version":1}"""
        }
        return """{"outcomes":[$outcomes],"changes":[],"next_cursor":$nextCursor,"has_more":false}"""
    }

    @Test
    fun http409WithAnotherErrorCode_sendsNoSnapshotRequest() = runTest {
        seedCursor(77L)
        val fixture = HttpFixture()
        try {
            fixture.respond { _, _ ->
                MockResponse().setResponseCode(409).setBody(errorBody("version_conflict", "entity changed"))
            }

            val result = fixture.httpEngine.sync(ROBUSTNESS_OWNER_ID)

            assertTrue("got $result", result is SyncResult.Error)
            assertEquals(listOf("/v1/sync"), fixture.requests.map { it.path })
            assertEquals(77L, storedCursor())
        } finally {
            fixture.shutdown()
        }
    }

    @Test
    fun http409CursorExpired_stillBootstraps() = runTest {
        seedCursor(77L)
        val fixture = HttpFixture()
        try {
            fixture.respond { request, body ->
                when (request.path) {
                    "/v1/snapshot" -> MockResponse().setResponseCode(200).setBody("""{"cursor":300,"has_more":false}""")
                    else -> if (body.contains("\"cursor\":77")) {
                        MockResponse().setResponseCode(409).setBody(errorBody("cursor_expired", "resync required"))
                    } else {
                        MockResponse().setResponseCode(200).setBody("""{"outcomes":[],"changes":[],"next_cursor":300,"has_more":false}""")
                    }
                }
            }

            val result = fixture.httpEngine.sync(ROBUSTNESS_OWNER_ID)

            assertTrue("got $result", result is SyncResult.Success)
            assertEquals(listOf("/v1/sync", "/v1/snapshot", "/v1/sync"), fixture.requests.map { it.path })
            assertEquals(300L, storedCursor())
        } finally {
            fixture.shutdown()
        }
    }

    @Test
    fun http400ForOneMutation_isIsolatedOverTheWire_andTheRestUploads() = runTest {
        for (i in 1..6) database.enqueueSolve("solve-$i", order = i)
        val fixture = HttpFixture()
        try {
            fixture.respond { _, body ->
                val request = json.decodeFromString(SyncRequest.serializer(), body)
                if (request.mutations.any { it.entityId == "solve-4" }) {
                    MockResponse().setResponseCode(400).setBody(errorBody("invalid_request", "json: unknown field"))
                } else {
                    MockResponse().setResponseCode(200).setBody(acceptedJson(request, nextCursor = 10L + fixture.requests.size))
                }
            }

            val result = fixture.httpEngine.sync(ROBUSTNESS_OWNER_ID)

            assertTrue("got $result", result is SyncResult.Success)
            assertEquals(5, (result as SyncResult.Success).mutationsSynced)
            val dead = database.syncOutboxDao().getMutationById("mut-solve-4")
            assertNotNull(dead)
            assertEquals("dead", dead!!.status)
            assertTrue(dead.lastError!!, dead.lastError.contains("json: unknown field"))
            assertEquals(0, database.syncOutboxDao().countPending(ROBUSTNESS_OWNER_ID))
        } finally {
            fixture.shutdown()
        }
    }

    @Test
    fun http413WithAnHtmlBody_isPermanent_andSolvedByShrinkingTheBatch() = runTest {
        for (i in 1..9) database.enqueueSolve("solve-$i", order = i)
        val fixture = HttpFixture()
        try {
            fixture.respond { _, body ->
                val request = json.decodeFromString(SyncRequest.serializer(), body)
                if (request.mutations.size > 3) {
                    MockResponse().setResponseCode(413).setBody("<html><body>413 Request Entity Too Large</body></html>")
                } else {
                    MockResponse().setResponseCode(200).setBody(acceptedJson(request, nextCursor = 10L + fixture.requests.size))
                }
            }

            val result = fixture.httpEngine.sync(ROBUSTNESS_OWNER_ID)

            assertEquals(9, (result as SyncResult.Success).mutationsSynced)
            assertEquals(0, database.syncOutboxDao().getAllPendingForOwner(ROBUSTNESS_OWNER_ID).size)
        } finally {
            fixture.shutdown()
        }
    }

    @Test
    fun http500_isTransient_rowsStayRetryable() = runTest {
        for (i in 1..3) database.enqueueSolve("solve-$i", order = i)
        val fixture = HttpFixture()
        try {
            fixture.respond { _, _ ->
                MockResponse().setResponseCode(500).setBody(errorBody("server_error", "boom"))
            }

            val result = fixture.httpEngine.sync(ROBUSTNESS_OWNER_ID)

            assertTrue("got $result", result is SyncResult.Error)
            assertEquals(1, fixture.requests.size)
            val rows = database.syncOutboxDao().getAllPendingForOwner(ROBUSTNESS_OWNER_ID)
            assertTrue(rows.map { it.status }.toString(), rows.size == 3 && rows.all { it.status == "failed" })
        } finally {
            fixture.shutdown()
        }
    }
}
