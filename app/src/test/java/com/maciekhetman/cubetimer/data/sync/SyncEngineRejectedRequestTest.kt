package com.maciekhetman.cubetimer.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.local.dao.SyncOutboxDao
import com.maciekhetman.cubetimer.data.remote.NetworkModule
import com.maciekhetman.cubetimer.data.remote.dto.ChangeDto
import com.maciekhetman.cubetimer.data.remote.dto.SolveSnapshotDto
import com.maciekhetman.cubetimer.data.remote.dto.SyncRequest
import com.maciekhetman.cubetimer.data.remote.dto.SyncResponse
import com.maciekhetman.cubetimer.model.AuthException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * One mutation the server rejects outright (a request-level 4xx: the server decodes strictly and
 * refuses the whole request) used to fail the same first batch on every sync, so nothing behind it
 * ever uploaded. The engine now isolates the offender within the same sync and dead-letters it,
 * while transient failures keep their retry-later behaviour.
 */
@RunWith(RobolectricTestRunner::class)
class SyncEngineRejectedRequestTest {

    private lateinit var database: CubeDatabase
    private lateinit var outboxDao: SyncOutboxDao
    private lateinit var api: ScriptedSyncApiClient
    private lateinit var engine: SyncEngineImpl
    private val json = NetworkModule.json

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = CubeDatabase.createInMemory(context)
        outboxDao = database.syncOutboxDao()
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

    private fun badRequest(message: String = "unknown field \"bogus\"") =
        AuthException.ApiError(errorCode = "invalid_request", message = message, httpStatusCode = 400)

    /** A server that refuses the whole request whenever it carries a mutation for one of [badSolveIds]. */
    private fun rejectRequestsCarrying(vararg badSolveIds: String, status: Int = 400) {
        api.syncHandler = { request: SyncRequest ->
            if (request.mutations.any { it.entityId in badSolveIds }) {
                throw AuthException.ApiError("invalid_request", "cannot decode mutation", status)
            }
            acceptAll(request)
        }
    }

    private suspend fun enqueueSolves(count: Int) {
        for (i in 1..count) database.enqueueSolve("solve-$i", order = i)
    }

    private suspend fun statusOf(mutationId: String) = outboxDao.getMutationById(mutationId)?.status

    // ------------------------------------------------------------------------------------------
    // Isolation and dead-lettering
    // ------------------------------------------------------------------------------------------

    @Test
    fun oneBadMutation_goodOnesUploadInTheSameSync_andTheBadOneIsDead() = runTest {
        enqueueSolves(8)
        rejectRequestsCarrying("solve-5")

        val result = engine.sync(ROBUSTNESS_OWNER_ID)

        assertTrue("got $result", result is SyncResult.Success)
        assertEquals("the seven good mutations were accepted", 7, (result as SyncResult.Success).mutationsSynced)
        for (i in listOf(1, 2, 3, 4, 6, 7, 8)) {
            assertNull("mut-solve-$i accepted and removed", outboxDao.getMutationById("mut-solve-$i"))
            assertEquals(1L, database.solveDao().getSolveById("solve-$i")?.version)
        }
        val dead = outboxDao.getMutationById("mut-solve-5")
        assertNotNull(dead)
        assertEquals("dead", dead!!.status)
        assertEquals(1, dead.attemptCount)
        assertTrue(dead.lastError!!, dead.lastError.contains("400") && dead.lastError.contains("cannot decode mutation"))
        assertEquals("the bad solve never got a server version", 0L, database.solveDao().getSolveById("solve-5")?.version)
        assertEquals("a dead row is not pending", 0, outboxDao.countPending(ROBUSTNESS_OWNER_ID))
    }

    @Test
    fun deadLetter_isSurfacedAsTheSyncError() = runTest {
        enqueueSolves(3)
        rejectRequestsCarrying("solve-2")

        engine.sync(ROBUSTNESS_OWNER_ID)

        val error = database.syncMetadataDao().getMetadata(ROBUSTNESS_OWNER_ID)?.lastError
        assertNotNull("the user has to hear that a change will never upload", error)
        assertTrue(error!!, error.contains("1 change was rejected by the server"))
        assertTrue(error, error.contains("cannot decode mutation"))
    }

    @Test
    fun deadMutation_isNeverResent_andLaterMutationsUpload() = runTest {
        enqueueSolves(4)
        rejectRequestsCarrying("solve-3")
        engine.sync(ROBUSTNESS_OWNER_ID)
        val requestsAfterFirstSync = api.syncRequests.size

        // The server is unchanged: it would reject mut-solve-3 again if it were ever sent.
        database.enqueueSolve("solve-9", order = 9)
        val second = engine.sync(ROBUSTNESS_OWNER_ID)

        assertTrue("got $second", second is SyncResult.Success)
        assertEquals(1, (second as SyncResult.Success).mutationsSynced)
        val laterRequests = api.syncRequests.drop(requestsAfterFirstSync)
        assertEquals(1, laterRequests.size)
        assertEquals(listOf("mut-solve-9"), laterRequests.single().mutations.map { it.id })
        assertNull(outboxDao.getMutationById("mut-solve-9"))
        assertEquals("dead", statusOf("mut-solve-3"))
        // A sync that completes without a new dead letter clears the error again.
        assertNull(database.syncMetadataDao().getMetadata(ROBUSTNESS_OWNER_ID)?.lastError)
    }

    @Test
    fun badMutationAtTheFront_isolatedInLogarithmicRequests() = runTest {
        enqueueSolves(64)
        rejectRequestsCarrying("solve-1")

        val result = engine.sync(ROBUSTNESS_OWNER_ID)

        assertEquals(63, (result as SyncResult.Success).mutationsSynced)
        assertEquals("dead", statusOf("mut-solve-1"))
        assertEquals(0, outboxDao.countPending(ROBUSTNESS_OWNER_ID))
        // 7 rejected halvings + 1 probe + 1 confirming retry + 6 accepted right halves = 15.
        assertTrue("took ${api.syncRequests.size} requests", api.syncRequests.size <= 20)
    }

    @Test
    fun severalBadMutations_areAllIsolated_andEveryGoodOneUploads() = runTest {
        enqueueSolves(16)
        rejectRequestsCarrying("solve-3", "solve-11", "solve-12")

        val result = engine.sync(ROBUSTNESS_OWNER_ID)

        assertEquals(13, (result as SyncResult.Success).mutationsSynced)
        for (bad in listOf(3, 11, 12)) assertEquals("dead", statusOf("mut-solve-$bad"))
        assertEquals(0, outboxDao.countPending(ROBUSTNESS_OWNER_ID))
        val error = database.syncMetadataDao().getMetadata(ROBUSTNESS_OWNER_ID)?.lastError
        assertTrue(error!!, error.contains("3 changes were rejected"))
    }

    @Test
    fun aSingleQueuedMutationThatIsRejected_isDeadLettered() = runTest {
        enqueueSolves(1)
        rejectRequestsCarrying("solve-1")

        val result = engine.sync(ROBUSTNESS_OWNER_ID)

        assertTrue("got $result", result is SyncResult.Success)
        assertEquals("dead", statusOf("mut-solve-1"))
    }

    @Test
    fun a413_isPermanentToo_andIsSolvedByShrinkingTheBatch() = runTest {
        enqueueSolves(10)
        // A body-size limit: anything with more than 3 mutations is too large.
        api.syncHandler = { request ->
            if (request.mutations.size > 3) throw AuthException.ApiError("unknown_error", "Request Entity Too Large", 413)
            acceptAll(request)
        }

        val result = engine.sync(ROBUSTNESS_OWNER_ID)

        assertEquals(10, (result as SyncResult.Success).mutationsSynced)
        assertEquals("nothing is dead: every mutation fits on its own", 0, database.syncOutboxDao().getAllPendingForOwner(ROBUSTNESS_OWNER_ID).size)
        assertNull(database.syncMetadataDao().getMetadata(ROBUSTNESS_OWNER_ID)?.lastError)
    }

    @Test
    fun deadLetteredNewestEdit_revivesTheOlderEditItSuperseded() = runTest {
        // Two queued edits of one entity: the server takes the older state but not the newer one.
        database.enqueueSolve("solve-x", order = 1, mutationId = "m-old", durationMs = 10_000L)
        database.enqueueSolve("solve-x", order = 2, mutationId = "m-new", durationMs = 11_000L, insertSolve = false)
        api.syncHandler = { request ->
            if (request.mutations.any { it.id == "m-new" }) throw badRequest()
            acceptAll(request)
        }

        val result = engine.sync(ROBUSTNESS_OWNER_ID)

        assertTrue("got $result", result is SyncResult.Success)
        assertEquals("dead", statusOf("m-new"))
        assertNull("the older edit got its own chance and was accepted", outboxDao.getMutationById("m-old"))
        assertEquals(1, (result as SyncResult.Success).mutationsSynced)
        assertTrue(api.sentMutationIds().contains("m-old"))
    }

    @Test
    fun deadMutation_noLongerFreezesItsEntityAgainstRemoteChanges() = runTest {
        enqueueSolves(1)
        rejectRequestsCarrying("solve-1")
        engine.sync(ROBUSTNESS_OWNER_ID)
        assertEquals("dead", statusOf("mut-solve-1"))

        // Another device changes the solve. While the local edit was still queued this change would
        // have been skipped; a dead edit can never upload, so the server's copy wins.
        val remote = SolveSnapshotDto(
            id = "solve-1", durationMs = 4_321L, solvedAt = "2026-08-30T09:00:00.000Z", version = 5L,
            updatedAt = "2026-08-30T12:00:00.000Z"
        )
        api.syncHandler = { request ->
            SyncResponse(
                changes = listOf(
                    ChangeDto(
                        cursor = 50L, entity = "solve", entityId = "solve-1", operation = "upsert",
                        version = 5L, data = json.encodeToJsonElement(SolveSnapshotDto.serializer(), remote)
                    )
                ),
                nextCursor = 50L
            )
        }

        val result = engine.sync(ROBUSTNESS_OWNER_ID)

        assertEquals(1, (result as SyncResult.Success).changesApplied)
        val solve = database.solveDao().getSolveById("solve-1")
        assertEquals(4_321L, solve?.durationMs)
        assertEquals(5L, solve?.version)
    }

    // ------------------------------------------------------------------------------------------
    // Not attributable to a mutation: nothing may be dead-lettered
    // ------------------------------------------------------------------------------------------

    @Test
    fun rejectionThatHitsEmptyRequestsToo_isNotTheMutationsFault_andNothingDies() = runTest {
        enqueueSolves(6)
        api.syncHandler = { throw badRequest("unsupported protocol") }

        val result = engine.sync(ROBUSTNESS_OWNER_ID)

        assertTrue("got $result", result is SyncResult.Error)
        val rows = outboxDao.getAllPendingForOwner(ROBUSTNESS_OWNER_ID)
        assertEquals(6, rows.size)
        assertTrue("every row stays retryable: ${rows.map { it.status }}", rows.all { it.status == "failed" })
        assertEquals(6, outboxDao.countPending(ROBUSTNESS_OWNER_ID))
        // The last request was the mutation-free probe.
        assertTrue(api.syncRequests.last().mutations.isEmpty())
    }

    @Test
    fun onceTheServerRecovers_rowsThatWereOnlyFailedUploadNormally() = runTest {
        enqueueSolves(6)
        api.syncHandler = { throw badRequest("unsupported protocol") }
        engine.sync(ROBUSTNESS_OWNER_ID)

        api.syncHandler = ::acceptAll
        val result = engine.sync(ROBUSTNESS_OWNER_ID)

        assertEquals(6, (result as SyncResult.Success).mutationsSynced)
        assertEquals(0, outboxDao.getAllPendingForOwner(ROBUSTNESS_OWNER_ID).size)
    }

    @Test
    fun aRejectionStormIsBoundedPerSync_andLeavesTheRestRetryable() = runTest {
        // The server accepts empty probes but refuses every mutation-bearing request.
        api.syncHandler = { request ->
            if (request.mutations.isEmpty()) acceptAll(request) else throw badRequest("nope")
        }
        enqueueSolves(400)

        val result = engine.sync(ROBUSTNESS_OWNER_ID)

        assertTrue("got $result", result is SyncResult.Error)
        val rows = outboxDao.getAllPendingForOwner(ROBUSTNESS_OWNER_ID)
        assertEquals("nothing is deleted", 400, rows.size)
        val dead = rows.count { it.status == "dead" }
        assertTrue("dead-lettering is capped per sync, was $dead", dead in 1..128)
        assertTrue("the rest is retryable", rows.filter { it.status != "dead" }.all { it.status == "failed" })
        assertTrue("bounded traffic: ${api.syncRequests.size}", api.syncRequests.size < 400)
    }

    // ------------------------------------------------------------------------------------------
    // Transient failures keep today's behaviour
    // ------------------------------------------------------------------------------------------

    @Test
    fun transientHttpFailures_leaveRowsRetryable_notDead() = runTest {
        for (status in listOf(408, 425, 500, 502, 503)) {
            outboxDao.clearOutbox(ROBUSTNESS_OWNER_ID)
            api.syncRequests.clear()
            enqueueSolves(3)
            api.syncHandler = { throw AuthException.ApiError("server_error", "try later", status) }

            val result = engine.sync(ROBUSTNESS_OWNER_ID)

            assertTrue("HTTP $status: got $result", result is SyncResult.Error)
            val rows = outboxDao.getAllPendingForOwner(ROBUSTNESS_OWNER_ID)
            assertEquals("HTTP $status", 3, rows.size)
            assertTrue("HTTP $status: ${rows.map { it.status }}", rows.all { it.status == "failed" && it.attemptCount == 1 })
            assertEquals("HTTP $status: no bisecting on a transient failure", 1, api.syncRequests.size)

            // ...and they upload once the server is back.
            api.syncHandler = ::acceptAll
            val retry = engine.sync(ROBUSTNESS_OWNER_ID)
            assertEquals("HTTP $status", 3, (retry as SyncResult.Success).mutationsSynced)
        }
    }

    @Test
    fun rateLimitAuthAndNetworkFailures_keepRowsRetryable() = runTest {
        val failures: List<Pair<Exception, (SyncResult) -> Boolean>> = listOf(
            Pair(AuthException.RateLimited(), { r: SyncResult -> r is SyncResult.Error }),
            Pair(AuthException.Unauthorized(), { r: SyncResult -> r is SyncResult.AuthError }),
            Pair(AuthException.Forbidden(), { r: SyncResult -> r is SyncResult.Error }),
            Pair(
                AuthException.ApiError(errorCode = "internal_error", message = "boom", httpStatusCode = 500),
                { r: SyncResult -> r is SyncResult.Error }
            ),
            Pair(AuthException.NetworkError("offline"), { r: SyncResult -> r is SyncResult.Offline }),
            Pair(IOException("connection reset"), { r: SyncResult -> r is SyncResult.Offline })
        )
        for ((failure, expectedResult) in failures) {
            outboxDao.clearOutbox(ROBUSTNESS_OWNER_ID)
            api.syncRequests.clear()
            enqueueSolves(2)
            api.syncHandler = { throw failure }

            val result = engine.sync(ROBUSTNESS_OWNER_ID)

            assertTrue("${failure::class.simpleName}: got $result", expectedResult(result))
            val rows = outboxDao.getAllPendingForOwner(ROBUSTNESS_OWNER_ID)
            assertTrue("${failure::class.simpleName}: ${rows.map { it.status }}", rows.size == 2 && rows.all { it.status == "failed" })
            assertEquals(1, api.syncRequests.size)
        }
    }

    @Test
    fun aRejectedPureRead_isReportedAsAnError_withNothingToDeadLetter() = runTest {
        api.syncHandler = { throw badRequest("bad cursor") }

        val result = engine.sync(ROBUSTNESS_OWNER_ID)

        assertTrue("got $result", result is SyncResult.Error)
        assertEquals(1, api.syncRequests.size)
    }

    @Test
    fun bulkFailureBookkeeping_coversAFullBatchOfFiveHundred() = runTest {
        enqueueSolves(500)
        api.syncHandler = { throw AuthException.ApiError("server_error", "down", 503) }

        engine.sync(ROBUSTNESS_OWNER_ID)

        val rows = outboxDao.getAllPendingForOwner(ROBUSTNESS_OWNER_ID, limit = 1_000)
        assertEquals(500, rows.size)
        assertTrue(rows.all { it.status == "failed" && it.attemptCount == 1 && it.lastError != null })
        assertFalse(rows.any { it.status == "in_flight" })
    }
}
