package com.maciekhetman.cubetimer.data.local

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.data.SolvesRepository
import com.maciekhetman.cubetimer.data.local.converter.CubeTypeConverters
import com.maciekhetman.cubetimer.data.local.dao.SolveDao
import com.maciekhetman.cubetimer.data.local.entity.SessionEntity
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import com.maciekhetman.cubetimer.data.session.SessionRepositoryImpl
import com.maciekhetman.cubetimer.model.Mode
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Bulk id operations must keep every `IN (...)` list under SQLite's pre-3.32 limit of 999 bind
 * variables (Android 11 / API 30 and below). Robolectric's SQLite is newer and wouldn't fail on
 * its own, so [OldSqliteSolveDao] rejects oversized lists the way those devices do.
 */
@RunWith(RobolectricTestRunner::class)
class BulkIdChunkingTest {

    private lateinit var context: Context
    private lateinit var database: CubeDatabase
    private lateinit var solveDao: OldSqliteSolveDao
    private lateinit var solvesRepository: SolvesRepository
    private lateinit var sessionRepository: SessionRepositoryImpl

    private val ownerId = "user-bulk"
    private val solveCount = 1_200

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = CubeDatabase.createInMemory(context)
        solveDao = OldSqliteSolveDao(database.solveDao())
        solvesRepository = SolvesRepository(
            context = context,
            solveDao = solveDao,
            sessionDao = database.sessionDao(),
            syncOutboxDao = database.syncOutboxDao(),
            database = database
        )
        sessionRepository = SessionRepositoryImpl(database = database, solveDao = solveDao)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun seedSessionWithSolves(sessionId: String = "bulk-session"): List<SolveEntity> {
        database.sessionDao().insert(
            SessionEntity(
                id = sessionId,
                ownerId = ownerId,
                name = "Bulk",
                event = "3x3",
                kind = "manual",
                startedAt = "2026-01-01T00:00:00.000Z"
            )
        )
        val base = 1_767_225_600_000L // 2026-01-01T00:00:00Z
        val solves = (0 until solveCount).map { i ->
            SolveEntity(
                id = "bulk-$i",
                ownerId = ownerId,
                sessionId = sessionId,
                event = "3x3",
                durationMs = 10_000L + i,
                solvedAt = CubeTypeConverters.epochMillisToIso(base + i * 1_000L)
            )
        }
        database.solveDao().insertAll(solves)
        return solves
    }

    @Test
    fun clearAllSolvesInScope_andRestore_handleMoreThan999Solves() = runTest {
        seedSessionWithSolves()

        val deleted = solvesRepository.clearAllSolvesInScope(Mode.CUBE_3x3, ownerId)

        assertEquals(solveCount, deleted.size)
        assertEquals(0, database.solveDao().getSolveCountByEvent(ownerId, "3x3"))
        assertEquals(solveCount, database.syncOutboxDao().countPending(ownerId))

        solvesRepository.restoreSolves(deleted, ownerId)

        assertEquals(solveCount, database.solveDao().getSolveCountByEvent(ownerId, "3x3"))
        assertTrue(solveDao.maxIdsPerCall in 1..999)
    }

    @Test
    fun deleteSolvesByIds_handlesMoreThan999Ids() = runTest {
        val solves = seedSessionWithSolves()

        val deleted = solvesRepository.deleteSolvesByIds(solves.map { it.id }, ownerId)

        assertEquals(solveCount, deleted.size)
        assertEquals(0, database.solveDao().getSolveCountByEvent(ownerId, "3x3"))
        assertTrue(solveDao.maxIdsPerCall in 1..999)
    }

    @Test
    fun deleteAndRestoreSessionWithSolves_handleMoreThan999Solves() = runTest {
        seedSessionWithSolves("big-session")

        val snapshot = sessionRepository.deleteSessionWithSolves("big-session", ownerId)

        assertNotNull(snapshot)
        assertEquals(solveCount, snapshot!!.solves.size)
        assertEquals(0, database.solveDao().getSolveCountBySession(ownerId, "big-session"))

        sessionRepository.restoreSessionWithSolves(snapshot, ownerId)

        assertEquals(solveCount, database.solveDao().getSolveCountBySession(ownerId, "big-session"))
        assertTrue(solveDao.maxIdsPerCall in 1..999)
    }

    @Test
    fun deleteSession_handlesMoreThan999Solves() = runTest {
        seedSessionWithSolves("plain-delete")

        assertTrue(sessionRepository.deleteSession("plain-delete", ownerId))

        assertEquals(0, database.solveDao().getSolveCountBySession(ownerId, "plain-delete"))
        assertTrue(solveDao.maxIdsPerCall in 1..999)
    }

    /** Mimics SQLite < 3.32: a statement with more than 999 bind variables fails to compile. */
    private class OldSqliteSolveDao(private val delegate: SolveDao) : SolveDao by delegate {
        var maxIdsPerCall = 0
            private set

        private fun check(ids: List<String>) {
            maxIdsPerCall = maxOf(maxIdsPerCall, ids.size)
            if (ids.size > 999) {
                throw SQLiteException("too many SQL variables (code 1 SQLITE_ERROR): ${ids.size} ids")
            }
        }

        override suspend fun getSolvesByIds(ids: List<String>): List<SolveEntity> {
            check(ids)
            return delegate.getSolvesByIds(ids)
        }

        override suspend fun softDeleteAll(ids: List<String>, deletedAt: String, updatedAt: String): Int {
            check(ids)
            return delegate.softDeleteAll(ids, deletedAt, updatedAt)
        }
    }
}
