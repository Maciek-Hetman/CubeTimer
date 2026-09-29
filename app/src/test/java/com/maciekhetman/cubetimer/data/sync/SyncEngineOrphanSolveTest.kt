package com.maciekhetman.cubetimer.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.remote.NetworkModule
import com.maciekhetman.cubetimer.data.remote.dto.ChangeDto
import com.maciekhetman.cubetimer.data.remote.dto.SessionSnapshotDto
import com.maciekhetman.cubetimer.data.remote.dto.SnapshotResponse
import com.maciekhetman.cubetimer.data.remote.dto.SolveSnapshotDto
import com.maciekhetman.cubetimer.data.remote.dto.SyncResponse
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
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
 * `solves.session_id` is a deferred foreign key, so a remote solve naming a session that is not in
 * the database fails the commit of the whole page it arrives in. The page is then retried on every
 * sync and the cursor never moves. Such a solve is skipped instead, and the rest of the page lands.
 */
@RunWith(RobolectricTestRunner::class)
class SyncEngineOrphanSolveTest {

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

    private fun sessionDto(id: String) = SessionSnapshotDto(
        id = id,
        name = "Session $id",
        event = "3x3",
        startedAt = "2026-08-30T07:00:00.000Z",
        version = 2L
    )

    private fun solveDto(id: String, sessionId: String? = null) = SolveSnapshotDto(
        id = id,
        sessionId = sessionId,
        durationMs = 9_000L,
        solvedAt = "2026-08-30T08:00:00.000Z",
        version = 2L
    )

    private fun upsert(cursor: Long, entity: String, id: String, data: JsonElement) = ChangeDto(
        cursor = cursor,
        entity = entity,
        entityId = id,
        operation = "upsert",
        version = 2L,
        data = data
    )

    /** A session payload the client cannot decode: `name` and `started_at` are required. */
    private fun undecodableSession(id: String) = buildJsonObject {
        put("id", id)
        put("event", "3x3")
    }

    @Test
    fun sync_pageWithUndecodableSession_skipsItsSolveAndAppliesTheRest() = runTest {
        api.syncHandler = {
            SyncResponse(
                changes = listOf(
                    upsert(11, "session", "sess-bad", undecodableSession("sess-bad")),
                    upsert(12, "solve", "solve-orphan", json.encodeToJsonElement(solveDto("solve-orphan", "sess-bad"))),
                    upsert(13, "session", "sess-good", json.encodeToJsonElement(sessionDto("sess-good"))),
                    upsert(14, "solve", "solve-in-good-session", json.encodeToJsonElement(solveDto("solve-in-good-session", "sess-good"))),
                    upsert(15, "solve", "solve-unassigned", json.encodeToJsonElement(solveDto("solve-unassigned")))
                ),
                nextCursor = 42L,
                hasMore = false
            )
        }

        val result = engine.sync(ROBUSTNESS_OWNER_ID)

        assertTrue("got $result", result is SyncResult.Success)
        assertEquals(3, (result as SyncResult.Success).changesApplied)
        assertEquals("the cursor moved past the page", 42L, database.syncMetadataDao().getMetadata(ROBUSTNESS_OWNER_ID)?.cursor)
        assertNotNull(database.sessionDao().getSessionById("sess-good"))
        assertNull(database.sessionDao().getSessionById("sess-bad"))
        assertEquals("sess-good", database.solveDao().getSolveById("solve-in-good-session")?.sessionId)
        assertNotNull(database.solveDao().getSolveById("solve-unassigned"))
        assertNull("the orphan is skipped, not re-parented", database.solveDao().getSolveById("solve-orphan"))
    }

    @Test
    fun sync_afterASkippedOrphan_thePageIsNotServedAgain() = runTest {
        api.syncHandler = { request ->
            if (request.cursor == 0L) {
                SyncResponse(
                    changes = listOf(upsert(12, "solve", "solve-orphan", json.encodeToJsonElement(solveDto("solve-orphan", "sess-bad")))),
                    nextCursor = 12L
                )
            } else {
                SyncResponse(nextCursor = request.cursor)
            }
        }

        assertTrue(engine.sync(ROBUSTNESS_OWNER_ID) is SyncResult.Success)
        assertTrue(engine.sync(ROBUSTNESS_OWNER_ID) is SyncResult.Success)

        assertEquals(listOf(0L, 12L), api.syncRequests.map { it.cursor })
    }

    @Test
    fun snapshotBootstrap_skipsSolvesOfMissingSessions_andStillCommitsTheCursor() = runTest {
        api.snapshotHandler = { request ->
            if (request.entity == "session") {
                SnapshotResponse(sessions = listOf(sessionDto("sess-1")), cursor = 77L, hasMore = false, nextEntity = "solve")
            } else {
                SnapshotResponse(
                    solves = listOf(
                        solveDto("solve-in-session", "sess-1"),
                        solveDto("solve-orphan", "sess-missing"),
                        solveDto("solve-unassigned")
                    ),
                    cursor = 77L,
                    hasMore = false
                )
            }
        }

        val cursor = engine.runSnapshotBootstrap(ROBUSTNESS_OWNER_ID)

        assertEquals(77L, cursor)
        assertEquals(77L, database.syncMetadataDao().getMetadata(ROBUSTNESS_OWNER_ID)?.cursor)
        assertEquals("sess-1", database.solveDao().getSolveById("solve-in-session")?.sessionId)
        assertNotNull(database.solveDao().getSolveById("solve-unassigned"))
        assertNull(database.solveDao().getSolveById("solve-orphan"))
    }
}
