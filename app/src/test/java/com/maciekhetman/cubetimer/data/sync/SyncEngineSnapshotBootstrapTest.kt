package com.maciekhetman.cubetimer.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.local.entity.SyncMetadataEntity
import com.maciekhetman.cubetimer.data.remote.NetworkModule
import com.maciekhetman.cubetimer.data.remote.dto.SessionSnapshotDto
import com.maciekhetman.cubetimer.data.remote.dto.SnapshotRequest
import com.maciekhetman.cubetimer.data.remote.dto.SnapshotResponse
import com.maciekhetman.cubetimer.data.remote.dto.SolveSnapshotDto
import com.maciekhetman.cubetimer.data.remote.dto.SyncResponse
import com.maciekhetman.cubetimer.model.AuthException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The snapshot bootstrap must page until the server says it is done, however many pages that is,
 * and must never commit its watermark cursor unless it really got to the end. Before, it stopped
 * after 100 pages (~50k rows) and committed the cursor anyway, silently dropping the rest of a
 * big account.
 */
@RunWith(RobolectricTestRunner::class)
class SyncEngineSnapshotBootstrapTest {

    private val zeroUuid = "00000000-0000-0000-0000-000000000000"

    private lateinit var database: CubeDatabase
    private lateinit var api: ScriptedSyncApiClient

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = CubeDatabase.createInMemory(context)
        api = ScriptedSyncApiClient()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun engine(maxSnapshotPages: Int = 50_000) = SyncEngineImpl(
        apiClient = api,
        tokenStorage = StubTokenStorage(),
        database = database,
        authManager = StubAuthManager(),
        json = NetworkModule.json,
        maxSnapshotPages = maxSnapshotPages
    )

    private fun session(i: Int) = SessionSnapshotDto(
        id = "sess-$i",
        name = "Session $i",
        event = "3x3",
        startedAt = "2026-08-30T07:00:00Z",
        version = 4L
    )

    private fun solve(i: Int) = SolveSnapshotDto(
        id = "solve-$i",
        durationMs = 9_000L + i,
        solvedAt = "2026-08-30T08:00:00Z",
        version = 4L
    )

    private suspend fun seedCursor(cursor: Long) {
        database.syncMetadataDao().upsert(
            SyncMetadataEntity(ownerId = ROBUSTNESS_OWNER_ID, cursor = cursor, deviceId = "robust-device")
        )
    }

    private suspend fun storedCursor(): Long? =
        database.syncMetadataDao().getMetadata(ROBUSTNESS_OWNER_ID)?.cursor

    /**
     * A server holding [sessions] sessions and [solves] solves, one row per page: session pages
     * chained by next_after_id, the last one handing over to the solve pages (has_more = false,
     * next_entity = "solve"), the last solve page ending the stream.
     */
    private fun oneRowPerPageServer(sessions: Int, solves: Int, cursor: Long) {
        api.snapshotHandler = { request ->
            if (request.entity == "session") {
                val index = if (request.afterId == zeroUuid) 1 else request.afterId.removePrefix("sess-").toInt() + 1
                if (index < sessions) {
                    SnapshotResponse(sessions = listOf(session(index)), cursor = cursor, hasMore = true,
                        nextEntity = "session", nextAfterId = "sess-$index")
                } else {
                    SnapshotResponse(sessions = listOf(session(index)), cursor = cursor, hasMore = false,
                        nextEntity = "solve")
                }
            } else {
                val index = if (request.afterId == zeroUuid) 1 else request.afterId.removePrefix("solve-").toInt() + 1
                if (index < solves) {
                    SnapshotResponse(solves = listOf(solve(index)), cursor = cursor, hasMore = true,
                        nextEntity = "solve", nextAfterId = "solve-$index")
                } else {
                    SnapshotResponse(solves = listOf(solve(index)), cursor = cursor, hasMore = false)
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // More than the old 100-page cap
    // ------------------------------------------------------------------------------------------

    @Test
    fun bootstrap_withMoreThan100Pages_landsEveryRowAndCommitsTheCursor() = runTest {
        oneRowPerPageServer(sessions = 130, solves = 45, cursor = 7_777L)

        val cursor = engine().runSnapshotBootstrap(ROBUSTNESS_OWNER_ID)

        assertEquals(7_777L, cursor)
        assertEquals("175 pages, well past the old cap of 100", 175, api.snapshotRequests.size)
        assertEquals(130, database.sessionDao().getAllActiveSessionsForOwner(ROBUSTNESS_OWNER_ID).size)
        assertEquals(45, database.solveDao().getAllActiveSolvesForOwner(ROBUSTNESS_OWNER_ID).size)
        // The very last rows are the ones the old cap lost.
        assertNotNull(database.sessionDao().getSessionById("sess-130"))
        assertNotNull(database.solveDao().getSolveById("solve-45"))
        assertEquals(7_777L, storedCursor())
    }

    @Test
    fun bootstrap_requestsFollowTheHandOverExactlyOnceEach() = runTest {
        oneRowPerPageServer(sessions = 3, solves = 2, cursor = 55L)

        engine().runSnapshotBootstrap(ROBUSTNESS_OWNER_ID)

        assertEquals(
            listOf("session" to zeroUuid, "session" to "sess-1", "session" to "sess-2", "solve" to zeroUuid, "solve" to "solve-1"),
            api.snapshotRequests.map { it.entity to it.afterId }
        )
    }

    @Test
    fun syncRecoveringFromExpiredCursor_pagesPastTheOldCapAndResumesAtTheSnapshotCursor() = runTest {
        oneRowPerPageServer(sessions = 60, solves = 60, cursor = 9_000L)
        api.syncHandler = { request ->
            if (request.cursor == 9_000L) SyncResponse(nextCursor = 9_000L, hasMore = false)
            else throw AuthException.CursorExpired("cursor ${request.cursor} expired")
        }
        seedCursor(12L)

        val result = engine().sync(ROBUSTNESS_OWNER_ID)

        assertTrue("got $result", result is SyncResult.Success)
        assertEquals(120, api.snapshotRequests.size)
        assertEquals(60, database.solveDao().getAllActiveSolvesForOwner(ROBUSTNESS_OWNER_ID).size)
        assertEquals(9_000L, storedCursor())
        assertEquals(9_000L, api.syncRequests.last().cursor)
    }

    // ------------------------------------------------------------------------------------------
    // No-progress guards: fail, and leave the cursor alone
    // ------------------------------------------------------------------------------------------

    @Test
    fun bootstrap_whenServerRepeatsTheSameNextAfterId_failsWithoutCommittingTheCursor() = runTest {
        seedCursor(100L)
        api.snapshotHandler = { request ->
            // Page 1 resumes after s-1, and so does every page after it: it never gets past s-1.
            SnapshotResponse(
                sessions = listOf(session(if (request.afterId == zeroUuid) 1 else 2)),
                cursor = 900L, hasMore = true, nextEntity = "session", nextAfterId = "sess-1"
            )
        }

        try {
            engine().runSnapshotBootstrap(ROBUSTNESS_OWNER_ID)
            fail("a bootstrap that goes round in circles must fail")
        } catch (e: SnapshotBootstrapException) {
            assertTrue(e.message, e.message!!.contains("not advancing"))
        }

        assertEquals("caught before a third identical request", 2, api.snapshotRequests.size)
        assertEquals("cursor must not move to the snapshot watermark", 100L, storedCursor())
    }

    @Test
    fun bootstrap_whenHasMoreComesWithoutNextAfterId_failsInsteadOfRestartingFromTheZeroUuid() = runTest {
        seedCursor(100L)
        api.snapshotHandler = {
            SnapshotResponse(sessions = listOf(session(1)), cursor = 900L, hasMore = true)
        }

        try {
            engine().runSnapshotBootstrap(ROBUSTNESS_OWNER_ID)
            fail("has_more without next_after_id has nowhere to go")
        } catch (e: SnapshotBootstrapException) {
            assertTrue(e.message, e.message!!.contains("next_after_id"))
        }

        assertEquals(1, api.snapshotRequests.size)
        assertEquals(100L, storedCursor())
    }

    @Test
    fun bootstrap_whenServerPingPongsBetweenEntities_fails() = runTest {
        seedCursor(100L)
        api.snapshotHandler = { request ->
            if (request.entity == "session") {
                SnapshotResponse(cursor = 900L, hasMore = false, nextEntity = "solve")
            } else {
                SnapshotResponse(cursor = 900L, hasMore = false, nextEntity = "session")
            }
        }

        try {
            engine().runSnapshotBootstrap(ROBUSTNESS_OWNER_ID)
            fail("session -> solve -> session revisits a position")
        } catch (e: SnapshotBootstrapException) {
            // expected
        }

        assertEquals(2, api.snapshotRequests.size)
        assertEquals(100L, storedCursor())
    }

    @Test
    fun bootstrap_safetyCeilingFailsABootstrapThatNeverEnds() = runTest {
        seedCursor(100L)
        // Always a fresh position, so no repeat is ever detected - only the ceiling can stop it.
        api.snapshotHandler = { request: SnapshotRequest ->
            val n = api.snapshotRequests.size
            SnapshotResponse(sessions = listOf(session(n)), cursor = 900L, hasMore = true,
                nextEntity = "session", nextAfterId = "endless-$n")
        }

        try {
            engine(maxSnapshotPages = 25).runSnapshotBootstrap(ROBUSTNESS_OWNER_ID)
            fail("an endless stream must trip the ceiling")
        } catch (e: SnapshotBootstrapException) {
            assertTrue(e.message, e.message!!.contains("25"))
        }

        assertEquals(25, api.snapshotRequests.size)
        assertEquals(100L, storedCursor())
    }

    @Test
    fun bootstrap_exactlyAtTheCeiling_isStillFine() = runTest {
        oneRowPerPageServer(sessions = 3, solves = 2, cursor = 55L) // 5 pages

        val cursor = engine(maxSnapshotPages = 5).runSnapshotBootstrap(ROBUSTNESS_OWNER_ID)

        assertEquals(55L, cursor)
        assertEquals(5, api.snapshotRequests.size)
    }

    // ------------------------------------------------------------------------------------------
    // A tripped guard inside sync(): an error now, a retry next time
    // ------------------------------------------------------------------------------------------

    @Test
    fun syncWhoseBootstrapStalls_endsInError_andTheNextSyncRunsTheBootstrapAgain() = runTest {
        seedCursor(12L)
        var serverIsBroken = true
        api.syncHandler = { request ->
            // Whatever cursor the client holds, until it has the snapshot's, the server calls it expired.
            if (request.cursor == 900L) SyncResponse(nextCursor = 900L, hasMore = false)
            else throw AuthException.CursorExpired("expired")
        }
        api.snapshotHandler = { request ->
            if (serverIsBroken) {
                SnapshotResponse(sessions = listOf(session(1)), cursor = 900L, hasMore = true,
                    nextEntity = "session", nextAfterId = request.afterId.takeIf { it != zeroUuid } ?: "sess-1")
            } else {
                SnapshotResponse(sessions = listOf(session(1)), solves = listOf(solve(1)), cursor = 900L, hasMore = false)
            }
        }
        val engine = engine()

        val first = engine.sync(ROBUSTNESS_OWNER_ID)

        assertTrue("got $first", first is SyncResult.Error)
        assertTrue((first as SyncResult.Error).cause is SnapshotBootstrapException)
        assertTrue("the watermark must not have been committed", storedCursor() != 900L)

        serverIsBroken = false
        val second = engine.sync(ROBUSTNESS_OWNER_ID)

        assertTrue("got $second", second is SyncResult.Success)
        assertEquals(900L, storedCursor())
        assertNotNull(database.solveDao().getSolveById("solve-1"))
    }
}
