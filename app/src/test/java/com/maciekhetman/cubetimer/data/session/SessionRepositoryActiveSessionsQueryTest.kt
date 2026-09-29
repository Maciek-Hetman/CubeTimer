package com.maciekhetman.cubetimer.data.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.local.entity.SessionEntity
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import com.maciekhetman.cubetimer.model.Mode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [SessionRepositoryImpl.getActiveSessions] (solve-save path, manual-session fallback) filters by
 * owner / event / deleted / archived in SQL via [com.maciekhetman.cubetimer.data.local.dao.SessionDao.getActiveSessionsByEvent]
 * rather than loading every session of the owner, and returns them newest first like
 * `observeActiveSessionsByEvent`. Also covers the chunked cascade delete / restore of sessions
 * holding more solves than SQLite's bound-parameter limit.
 */
@RunWith(RobolectricTestRunner::class)
class SessionRepositoryActiveSessionsQueryTest {

    private lateinit var database: CubeDatabase
    private lateinit var repository: SessionRepositoryImpl

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = CubeDatabase.createInMemory(context)
        repository = SessionRepositoryImpl(database = database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun session(
        id: String,
        ownerId: String = "owner-a",
        event: String = "3x3",
        startedAt: String,
        archived: Boolean = false,
        deletedAt: String? = null
    ) = SessionEntity(
        id = id, ownerId = ownerId, name = id, event = event, kind = "manual",
        startedAt = startedAt, archived = archived, deletedAt = deletedAt
    )

    @Test
    fun getActiveSessionsByEvent_filtersInSqlAndOrdersNewestFirst() = runTest {
        val dao = database.sessionDao()
        dao.upsertAll(
            listOf(
                session("old", startedAt = "2026-08-01T10:00:00.000Z"),
                session("new", startedAt = "2026-08-03T10:00:00.000Z"),
                session("mid", startedAt = "2026-08-02T10:00:00.000Z"),
                session("archived", startedAt = "2026-08-04T10:00:00.000Z", archived = true),
                session("deleted", startedAt = "2026-08-05T10:00:00.000Z", deletedAt = "2026-08-06T00:00:00.000Z"),
                session("other-event", event = "2x2", startedAt = "2026-08-07T10:00:00.000Z"),
                session("other-owner", ownerId = "owner-b", startedAt = "2026-08-08T10:00:00.000Z")
            )
        )

        val fromDao = dao.getActiveSessionsByEvent("owner-a", "3x3")
        assertEquals(listOf("new", "mid", "old"), fromDao.map { it.id })
        // Same rows and order as the reactive query the UI uses.
        assertEquals(dao.observeActiveSessionsByEvent("owner-a", "3x3").first(), fromDao)

        val fromRepo = repository.getActiveSessions("owner-a", Mode.CUBE_3x3)
        assertEquals(listOf("new", "mid", "old"), fromRepo.map { it.id })
        assertEquals(listOf("other-event"), repository.getActiveSessions("owner-a", Mode.CUBE_2x2).map { it.id })
        assertEquals(emptyList<String>(), repository.getActiveSessions("nobody", Mode.CUBE_3x3).map { it.id })
    }

    @Test
    fun deleteAndRestoreSessionWithMoreSolvesThanBindLimit_isChunked() = runTest {
        val sessionEntity = session("big", ownerId = "guest", startedAt = "2026-08-01T00:00:00.000Z")
        database.sessionDao().upsert(sessionEntity)
        val solves = (0 until 2100).map {
            SolveEntity(
                id = "solve-$it", ownerId = "guest", sessionId = "big", event = "3x3",
                durationMs = 10_000L + it, solvedAt = "2026-08-01T00:00:00.000Z"
            )
        }
        database.solveDao().upsertAll(solves)

        val snapshot = repository.deleteSessionWithSolves("big", "guest")
        assertNotNull(snapshot)
        assertEquals(2100, snapshot!!.solves.size)
        assertEquals(0, database.solveDao().getSolvesBySession("guest", "big").size)
        assertNotNull(database.sessionDao().getSessionById("big")!!.deletedAt)

        repository.restoreSessionWithSolves(snapshot, "guest")
        assertEquals(2100, database.solveDao().getSolvesBySession("guest", "big").size)
        assertNull(database.sessionDao().getSessionById("big")!!.deletedAt)

        // Plain deleteSession cascades through the same chunked soft-delete.
        repository.deleteSession("big", "guest")
        assertEquals(0, database.solveDao().getSolvesBySession("guest", "big").size)
    }
}
