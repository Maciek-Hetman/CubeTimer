package com.maciekhetman.cubetimer.data.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.local.entity.ConflictEntity
import com.maciekhetman.cubetimer.data.local.entity.SessionEntity
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import com.maciekhetman.cubetimer.data.local.entity.SyncMetadataEntity
import com.maciekhetman.cubetimer.data.local.entity.SyncOutboxEntity
import com.maciekhetman.cubetimer.data.remote.AuthInterceptor
import com.maciekhetman.cubetimer.data.remote.NetworkModule
import com.maciekhetman.cubetimer.model.AuthException
import com.maciekhetman.cubetimer.model.AuthState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Deleting the account is `DELETE /v1/me`; only once the server has accepted it does the client sign
 * out and turn the user's rows into never-synced guest data. Any failure leaves everything as it was.
 */
@RunWith(RobolectricTestRunner::class)
class AuthManagerDeleteAccountTest {

    private lateinit var server: MockWebServer
    private lateinit var database: CubeDatabase
    private lateinit var storage: ThreadSafeFakeTokenStorage
    private lateinit var authManager: AuthManagerImpl

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val context: Context = ApplicationProvider.getApplicationContext()
        database = CubeDatabase.createInMemory(context)
        storage = ThreadSafeFakeTokenStorage()
        authManager = AuthManagerImpl(
            apiClient = NetworkModule.provideCubeSyncApiClient(
                NetworkModule.provideAuthApiService(
                    server.url("/").toString(),
                    OkHttpClient.Builder().addInterceptor(AuthInterceptor(storage)).build()
                )
            ),
            tokenStorage = storage,
            database = database,
            ioDispatcher = Dispatchers.IO,
            authScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            autoInitialize = false
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
        database.close()
    }

    private suspend fun signIn() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                    "access_token": "access-1",
                    "refresh_token": "refresh-1",
                    "token_type": "Bearer",
                    "expires_in": 900,
                    "user": {"id": "$USER", "email": "cuber@example.com", "user_role": "user", "email_verified": true}
                }
                """.trimIndent()
            )
        )
        authManager.login("cuber@example.com", "ValidPassword123!")
        server.takeRequest() // the login itself
    }

    private fun solve(id: String, owner: String, version: Long, deletedAt: String? = null) = SolveEntity(
        id = id,
        ownerId = owner,
        sessionId = "sess-$owner",
        durationMs = 12000L,
        solvedAt = "2026-09-11T10:00:00.000Z",
        version = version,
        deletedAt = deletedAt
    )

    private fun outbox(id: String, owner: String) = SyncOutboxEntity(
        id = id,
        ownerId = owner,
        entityType = "solve",
        entityId = "solve-$owner",
        action = "upsert",
        clientTime = "2026-09-11T10:00:00.000Z"
    )

    private fun conflict(id: String, owner: String) = ConflictEntity(
        conflictId = id,
        ownerId = owner,
        mutationId = "m-$id",
        entityType = "solve",
        entityId = "solve-$owner",
        createdAt = "2026-09-11T10:00:00.000Z"
    )

    /** Signed-in user's rows (live + soft-deleted, with server versions, sync bookkeeping) next to another account's. */
    private suspend fun seedLocalData() {
        database.sessionDao().insertAll(
            listOf(
                SessionEntity(id = "sess-$USER", ownerId = USER, name = "Mine", startedAt = "2026-09-11T10:00:00.000Z", version = 5L),
                SessionEntity(id = "sess-other", ownerId = OTHER, name = "Theirs", startedAt = "2026-09-11T10:00:00.000Z", version = 7L)
            )
        )
        database.solveDao().insertAll(
            listOf(
                solve("mine-live", USER, version = 3L).copy(sessionId = "sess-$USER"),
                solve("mine-gone", USER, version = 2L, deletedAt = "2026-09-11T10:05:00.000Z").copy(sessionId = "sess-$USER"),
                solve("theirs", OTHER, version = 9L).copy(sessionId = "sess-other")
            )
        )
        database.syncOutboxDao().enqueueAll(listOf(outbox("o-mine-1", USER), outbox("o-mine-2", USER), outbox("o-theirs", OTHER)))
        database.conflictDao().insertAll(listOf(conflict("c-mine", USER), conflict("c-theirs", OTHER)))
        database.syncMetadataDao().upsert(SyncMetadataEntity(ownerId = USER, cursor = 42L, deviceId = "device-1"))
        database.syncMetadataDao().upsert(SyncMetadataEntity(ownerId = OTHER, cursor = 7L, deviceId = "device-1"))
    }

    private suspend fun assertLocalDataUntouched() {
        assertEquals(setOf("mine-live", "mine-gone"), database.solveDao().getAllSolvesForOwner(USER).map { it.id }.toSet())
        assertEquals(3L, database.solveDao().getSolveById("mine-live")!!.version)
        assertEquals(listOf("sess-$USER"), database.sessionDao().getAllSessionsForOwner(USER).map { it.id })
        assertEquals(5L, database.sessionDao().getSessionById("sess-$USER")!!.version)
        assertEquals(2, database.syncOutboxDao().countPending(USER))
        assertEquals(1, database.conflictDao().getAll(USER).size)
        assertEquals(42L, database.syncMetadataDao().getMetadata(USER)!!.cursor)
        assertTrue(database.solveDao().getAllSolvesForOwner("guest").isEmpty())
    }

    @Test
    fun success_signsOutAndKeepsTheUsersDataAsNeverSyncedGuestData() = runBlocking {
        signIn()
        seedLocalData()
        server.enqueue(MockResponse().setResponseCode(204))

        val result = authManager.deleteAccount()

        assertEquals(AuthResult.Success(Unit), result)
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/v1/me", request.path)
        assertEquals("Bearer access-1", request.getHeader("Authorization"))

        assertEquals(AuthState.Guest, authManager.authState.value)
        assertNull(authManager.currentUser)
        assertNull(storage.getAccessToken())
        assertNull(storage.getRefreshToken())
        assertNull(storage.getCachedUser())

        val solves = database.solveDao().getAllSolvesForOwner("guest")
        assertEquals(setOf("mine-live", "mine-gone"), solves.map { it.id }.toSet())
        assertTrue("guest rows never carry a server version", solves.all { it.version == 0L })
        assertEquals("soft deletes stay soft deletes", "2026-09-11T10:05:00.000Z", solves.first { it.id == "mine-gone" }.deletedAt)
        assertEquals(listOf("mine-live"), database.solveDao().getSolvesByScope("guest").map { it.id })
        val sessions = database.sessionDao().getAllSessionsForOwner("guest")
        assertEquals(listOf("sess-$USER"), sessions.map { it.id })
        assertEquals(0L, sessions.single().version)
        assertTrue(database.solveDao().getAllSolvesForOwner(USER).isEmpty())
        assertTrue(database.sessionDao().getAllSessionsForOwner(USER).isEmpty())

        assertEquals(0, database.syncOutboxDao().getAllPendingForOwner(USER).size)
        assertEquals(0, database.syncOutboxDao().getAllPendingForOwner("guest").size)
        assertTrue(database.conflictDao().getAll(USER).isEmpty())
        assertNull(database.syncMetadataDao().getMetadata(USER))
    }

    @Test
    fun success_leavesOtherAccountsRowsAlone() = runBlocking {
        signIn()
        seedLocalData()
        server.enqueue(MockResponse().setResponseCode(204))

        authManager.deleteAccount()

        assertEquals(9L, database.solveDao().getSolveById("theirs")!!.version)
        assertEquals(OTHER, database.solveDao().getSolveById("theirs")!!.ownerId)
        assertEquals(OTHER, database.sessionDao().getSessionById("sess-other")!!.ownerId)
        assertEquals(1, database.syncOutboxDao().countPending(OTHER))
        assertEquals(1, database.conflictDao().getAll(OTHER).size)
        assertEquals(7L, database.syncMetadataDao().getMetadata(OTHER)!!.cursor)
    }

    @Test
    fun serverRejection_staysSignedInAndChangesNothing() = runBlocking {
        for (status in listOf(401, 403, 429, 500, 503)) {
            signIn()
            seedLocalData()
            server.enqueue(MockResponse().setResponseCode(status).setBody("""{"error":{"code":"nope","message":"internal detail"}}"""))

            val result = authManager.deleteAccount()

            assertTrue("HTTP $status: $result", result is AuthResult.Error)
            assertNotNull((result as AuthResult.Error).exception)
            assertEquals("HTTP $status", "DELETE", server.takeRequest().method)
            assertTrue("HTTP $status", authManager.authState.value is AuthState.Authenticated)
            assertEquals("HTTP $status", "access-1", storage.getAccessToken())
            assertEquals("HTTP $status", "refresh-1", storage.getRefreshToken())
            assertLocalDataUntouched()

            database.clearAllTables()
        }
    }

    @Test
    fun networkFailure_staysSignedInAndChangesNothing() = runBlocking {
        signIn()
        seedLocalData()
        // Twice: OkHttp retries a request whose connection died before any response arrived.
        repeat(2) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)) }

        val result = authManager.deleteAccount()

        assertTrue(result is AuthResult.Error)
        assertTrue((result as AuthResult.Error).exception is AuthException.NetworkError)
        assertTrue(authManager.authState.value is AuthState.Authenticated)
        assertEquals("access-1", storage.getAccessToken())
        assertEquals("refresh-1", storage.getRefreshToken())
        assertLocalDataUntouched()
    }

    @Test
    fun guest_cannotDeleteAndSendsNoRequest() = runBlocking {
        authManager.initialize() // no stored session: Guest

        val result = authManager.deleteAccount()

        assertTrue(result is AuthResult.Error)
        assertTrue((result as AuthResult.Error).exception is AuthException.Unauthorized)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun success_stillSignsOutWhenTheLocalDatabaseWriteFails() = runBlocking {
        signIn()
        server.enqueue(MockResponse().setResponseCode(204))
        database.close() // the re-own transaction can no longer run

        val result = authManager.deleteAccount()

        assertEquals(AuthResult.Success(Unit), result)
        assertEquals(AuthState.Guest, authManager.authState.value)
        assertNull(storage.getRefreshToken())
        assertNull(storage.getAccessToken())
    }

    private companion object {
        const val USER = "user-1"
        const val OTHER = "user-2"
    }
}
