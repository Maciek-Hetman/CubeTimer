package com.maciekhetman.cubetimer.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.maciekhetman.cubetimer.data.local.dao.SolveDao
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class SolveDaoPaginationTest {

    private lateinit var database: CubeDatabase
    private lateinit var solveDao: SolveDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = CubeDatabase.createInMemory(context)
        solveDao = database.solveDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testGetSolvesPagedByEventReturnsOrderedChunks() = runTest {
        val baseTime = Instant.parse("2026-08-30T10:00:00.000Z")
        val solves = (0 until 75).map { i ->
            SolveEntity(
                id = "solve-$i",
                ownerId = "guest",
                event = "3x3",
                durationMs = 10000L + i * 10,
                penalty = "none",
                solvedAt = baseTime.plus(i.toLong(), ChronoUnit.MINUTES).toString(),
                scramble = "R U R' U' #$i",
                version = 0L
            )
        }
        solveDao.insertAll(solves)

        // Page 0 (limit 50, offset 0) -> should return solves 74 down to 25
        val page0 = solveDao.getSolvesPagedByEvent("guest", "3x3", limit = 50, offset = 0)
        assertEquals(50, page0.size)
        assertEquals("solve-74", page0.first().id)
        assertEquals("solve-25", page0.last().id)

        // Page 1 (limit 50, offset 50) -> should return solves 24 down to 0
        val page1 = solveDao.getSolvesPagedByEvent("guest", "3x3", limit = 50, offset = 50)
        assertEquals(25, page1.size)
        assertEquals("solve-24", page1.first().id)
        assertEquals("solve-0", page1.last().id)
    }

    @Test
    fun testGetSolvesPagedBySessionFiltersAndExcludesDeleted() = runTest {
        val targetSession = "session-target"
        val otherSession = "session-other"

        database.sessionDao().insert(
            com.maciekhetman.cubetimer.data.local.entity.SessionEntity(
                id = targetSession,
                ownerId = "guest",
                name = "Target Session",
                event = "3x3",
                kind = "manual",
                startedAt = "2026-08-30T09:00:00.000Z",
                updatedAt = "2026-08-30T09:00:00.000Z"
            )
        )
        database.sessionDao().insert(
            com.maciekhetman.cubetimer.data.local.entity.SessionEntity(
                id = otherSession,
                ownerId = "guest",
                name = "Other Session",
                event = "3x3",
                kind = "manual",
                startedAt = "2026-08-30T09:00:00.000Z",
                updatedAt = "2026-08-30T09:00:00.000Z"
            )
        )

        val s1 = SolveEntity(
            id = "s1",
            ownerId = "guest",
            sessionId = targetSession,
            event = "3x3",
            durationMs = 12000L,
            penalty = "none",
            solvedAt = "2026-08-30T10:01:00.000Z",
            scramble = "R U",
            version = 0L
        )
        val s2 = SolveEntity(
            id = "s2",
            ownerId = "guest",
            sessionId = targetSession,
            event = "3x3",
            durationMs = 11000L,
            penalty = "none",
            solvedAt = "2026-08-30T10:02:00.000Z",
            scramble = "R U'",
            version = 0L
        )
        val sDeleted = SolveEntity(
            id = "s-del",
            ownerId = "guest",
            sessionId = targetSession,
            event = "3x3",
            durationMs = 9000L,
            penalty = "none",
            solvedAt = "2026-08-30T10:03:00.000Z",
            scramble = "R U2",
            version = 0L,
            deletedAt = "2026-08-30T10:05:00.000Z"
        )
        val sOther = SolveEntity(
            id = "s-other",
            ownerId = "guest",
            sessionId = otherSession,
            event = "3x3",
            durationMs = 13000L,
            penalty = "none",
            solvedAt = "2026-08-30T10:04:00.000Z",
            scramble = "R U R'",
            version = 0L
        )
        solveDao.insertAll(listOf(s1, s2, sDeleted, sOther))

        val paged = solveDao.getSolvesPagedBySession("guest", targetSession, limit = 50, offset = 0)
        assertEquals(2, paged.size)
        // Descending order: s2 (10:02), then s1 (10:01)
        assertEquals("s2", paged[0].id)
        assertEquals("s1", paged[1].id)
    }

    @Test
    fun testObserveSolveCountFlows() = runTest {
        solveDao.observeSolveCountByEvent("guest", "3x3").test {
            assertEquals(0, awaitItem())

            val s1 = SolveEntity(
                id = "s1",
                ownerId = "guest",
                event = "3x3",
                durationMs = 10000L,
                penalty = "none",
                solvedAt = "2026-08-30T10:00:00.000Z",
                scramble = "R",
                version = 0L
            )
            solveDao.insert(s1)
            assertEquals(1, awaitItem())

            solveDao.softDelete("s1", deletedAt = "2026-08-30T10:01:00.000Z", updatedAt = "2026-08-30T10:01:00.000Z")
            assertEquals(0, awaitItem())
        }
    }

    @Test
    fun testObserveSolveCountBySession() = runTest {
        val sessionId = "session-123"
        database.sessionDao().insert(
            com.maciekhetman.cubetimer.data.local.entity.SessionEntity(
                id = sessionId,
                ownerId = "guest",
                name = "Session 123",
                event = "3x3",
                kind = "manual",
                startedAt = "2026-08-30T09:00:00.000Z",
                updatedAt = "2026-08-30T09:00:00.000Z"
            )
        )
        database.sessionDao().insert(
            com.maciekhetman.cubetimer.data.local.entity.SessionEntity(
                id = "different-session",
                ownerId = "guest",
                name = "Different Session",
                event = "3x3",
                kind = "manual",
                startedAt = "2026-08-30T09:00:00.000Z",
                updatedAt = "2026-08-30T09:00:00.000Z"
            )
        )

        solveDao.observeSolveCountBySession("guest", sessionId).distinctUntilChanged().test {
            assertEquals(0, awaitItem())

            val s1 = SolveEntity(
                id = "s1",
                ownerId = "guest",
                sessionId = sessionId,
                event = "3x3",
                durationMs = 10000L,
                penalty = "none",
                solvedAt = "2026-08-30T10:00:00.000Z",
                scramble = "R",
                version = 0L
            )
            solveDao.insert(s1)
            assertEquals(1, awaitItem())

            // Add solve in different session
            val sOther = SolveEntity(
                id = "s-other",
                ownerId = "guest",
                sessionId = "different-session",
                event = "3x3",
                durationMs = 10000L,
                penalty = "none",
                solvedAt = "2026-08-30T10:01:00.000Z",
                scramble = "R",
                version = 0L
            )
            solveDao.insert(sOther)
            // Count for session-123 should still be 1, distinctUntilChanged suppresses table invalidation re-emission
            expectNoEvents()
        }
    }

    @Test
    fun testGetPriorBestSolveDuration() = runTest {
        val t0 = "2026-08-30T10:00:00.000Z"
        val t1 = "2026-08-30T10:01:00.000Z"
        val t2 = "2026-08-30T10:02:00.000Z"
        val t3 = "2026-08-30T10:03:00.000Z"
        val t4 = "2026-08-30T10:04:00.000Z"

        // Initially no prior solves
        val initialPrior = solveDao.getPriorBestSolveDuration("guest", "3x3", t1)
        assertNull(initialPrior)

        // Insert solve 1 at t0: 15.00s
        solveDao.insert(
            SolveEntity(
                id = "s1",
                ownerId = "guest",
                event = "3x3",
                durationMs = 15000L,
                penalty = "none",
                solvedAt = t0,
                scramble = "R",
                version = 0L
            )
        )

        // At t1, prior best should be 15000L
        assertEquals(15000L, solveDao.getPriorBestSolveDuration("guest", "3x3", t1))

        // Insert solve 2 at t1: 10.00s + 2 penalty = 12.00s effective
        solveDao.insert(
            SolveEntity(
                id = "s2",
                ownerId = "guest",
                event = "3x3",
                durationMs = 10000L,
                penalty = "plus_two",
                solvedAt = t1,
                scramble = "R U",
                version = 0L
            )
        )

        // At t2, prior best should be 12000L (10000 + 2000)
        assertEquals(12000L, solveDao.getPriorBestSolveDuration("guest", "3x3", t2))

        // Insert solve 3 at t2: 8.00s with DNF penalty
        solveDao.insert(
            SolveEntity(
                id = "s3",
                ownerId = "guest",
                event = "3x3",
                durationMs = 8000L,
                penalty = "dnf",
                solvedAt = t2,
                scramble = "R U2",
                version = 0L
            )
        )

        // At t3, prior best should STILL be 12000L because DNF is ignored
        assertEquals(12000L, solveDao.getPriorBestSolveDuration("guest", "3x3", t3))

        // Insert solve 4 at t3: 9.50s clean
        solveDao.insert(
            SolveEntity(
                id = "s4",
                ownerId = "guest",
                event = "3x3",
                durationMs = 9500L,
                penalty = "none",
                solvedAt = t3,
                scramble = "R U'",
                version = 0L
            )
        )

        // At t4, prior best should be 9500L
        assertEquals(9500L, solveDao.getPriorBestSolveDuration("guest", "3x3", t4))

        // Verify getPriorBestSolve returns s4
        val bestEntity = solveDao.getPriorBestSolve("guest", "3x3", t4)
        assertNotNull(bestEntity)
        assertEquals("s4", bestEntity?.id)
        assertEquals(9500L, bestEntity?.durationMs)
    }
}
