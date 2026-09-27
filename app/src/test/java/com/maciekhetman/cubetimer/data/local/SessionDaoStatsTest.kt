package com.maciekhetman.cubetimer.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.maciekhetman.cubetimer.data.local.dao.SessionDao
import com.maciekhetman.cubetimer.data.local.dao.SolveDao
import com.maciekhetman.cubetimer.data.local.entity.SessionEntity
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SessionDaoStatsTest {

    private lateinit var database: CubeDatabase
    private lateinit var sessionDao: SessionDao
    private lateinit var solveDao: SolveDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = CubeDatabase.createInMemory(context)
        sessionDao = database.sessionDao()
        solveDao = database.solveDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testSessionWithZeroSolves_emitsZeroCountAndNullBestAndAvg() = runTest {
        val session = SessionEntity(
            id = "sess-empty",
            ownerId = "guest",
            name = "Empty Session",
            event = "3x3",
            kind = "manual",
            startedAt = "2026-09-11T10:00:00.000Z"
        )
        sessionDao.insert(session)

        val statsList = sessionDao.observeSessionsWithStats("guest", event = "3x3").first()
        assertEquals(1, statsList.size)
        val stats = statsList[0]
        assertEquals("sess-empty", stats.session.id)
        assertEquals(0, stats.solveCount)
        assertNull("Best duration must be null when solve count is 0", stats.bestDurationMs)
        assertNull("Average duration must be null when solve count is 0", stats.avgDurationMs)
    }

    @Test
    fun testSessionWithSolves_calculatesBestAndAvgWithPlusTwoAndExcludesDnf() = runTest {
        val session = SessionEntity(
            id = "sess-solves",
            ownerId = "guest",
            name = "Mixed Solves Session",
            event = "3x3",
            kind = "manual",
            startedAt = "2026-09-11T10:00:00.000Z"
        )
        sessionDao.insert(session)

        // Solve 1: Clean 12.00s (12000ms)
        val s1 = SolveEntity(
            id = "s-1",
            ownerId = "guest",
            sessionId = "sess-solves",
            event = "3x3",
            durationMs = 12000L,
            penalty = "none",
            solvedAt = "2026-09-11T10:01:00.000Z"
        )
        // Solve 2: 9.00s with +2 -> effective 11000ms (best single!)
        val s2 = SolveEntity(
            id = "s-2",
            ownerId = "guest",
            sessionId = "sess-solves",
            event = "3x3",
            durationMs = 9000L,
            penalty = "plus_two",
            solvedAt = "2026-09-11T10:02:00.000Z"
        )
        // Solve 3: 8.00s with DNF -> raw time 8000ms is fastest, but DNF MUST be excluded from best & avg
        val s3 = SolveEntity(
            id = "s-3",
            ownerId = "guest",
            sessionId = "sess-solves",
            event = "3x3",
            durationMs = 8000L,
            penalty = "dnf",
            solvedAt = "2026-09-11T10:03:00.000Z"
        )
        // Solve 4: Clean 13.00s (13000ms)
        val s4 = SolveEntity(
            id = "s-4",
            ownerId = "guest",
            sessionId = "sess-solves",
            event = "3x3",
            durationMs = 13000L,
            penalty = "none",
            solvedAt = "2026-09-11T10:04:00.000Z"
        )
        solveDao.insertAll(listOf(s1, s2, s3, s4))

        val statsList = sessionDao.observeSessionsWithStats("guest", event = "3x3").first()
        assertEquals(1, statsList.size)
        val stats = statsList[0]

        // Solve count includes DNF
        assertEquals(4, stats.solveCount)

        // Best is min(12000, 9000+2000=11000, 13000) = 11000ms (s2)
        assertEquals(11000L, stats.bestDurationMs)

        // Avg is mean(12000, 11000, 13000) = 36000 / 3 = 12000ms
        assertEquals(12000L, stats.avgDurationMs)
    }

    @Test
    fun testSessionWithOnlyDnfSolves_emitsCountNAndNullBestAndAvg() = runTest {
        val session = SessionEntity(
            id = "sess-all-dnf",
            ownerId = "guest",
            name = "All DNF Session",
            event = "3x3",
            kind = "manual",
            startedAt = "2026-09-11T10:00:00.000Z"
        )
        sessionDao.insert(session)

        val s1 = SolveEntity(
            id = "s-dnf-1",
            ownerId = "guest",
            sessionId = "sess-all-dnf",
            event = "3x3",
            durationMs = 10500L,
            penalty = "dnf",
            solvedAt = "2026-09-11T10:01:00.000Z"
        )
        val s2 = SolveEntity(
            id = "s-dnf-2",
            ownerId = "guest",
            sessionId = "sess-all-dnf",
            event = "3x3",
            durationMs = 11200L,
            penalty = "dnf",
            solvedAt = "2026-09-11T10:02:00.000Z"
        )
        solveDao.insertAll(listOf(s1, s2))

        val stats = sessionDao.observeSessionsWithStats("guest", event = "3x3").first()[0]
        assertEquals(2, stats.solveCount)
        assertNull("Best duration must be null when all solves are DNF", stats.bestDurationMs)
        assertNull("Average duration must be null when all solves are DNF", stats.avgDurationMs)
    }

    @Test
    fun testSoftDeletedSolvesExcludedFromSessionStats() = runTest {
        val session = SessionEntity(
            id = "sess-soft-del-solves",
            ownerId = "guest",
            name = "Soft Delete Solves Session",
            event = "3x3",
            startedAt = "2026-09-11T10:00:00.000Z"
        )
        sessionDao.insert(session)

        val activeSolve = SolveEntity(
            id = "s-act",
            ownerId = "guest",
            sessionId = "sess-soft-del-solves",
            event = "3x3",
            durationMs = 15000L,
            penalty = "none",
            solvedAt = "2026-09-11T10:01:00.000Z"
        )
        val deletedSolve = SolveEntity(
            id = "s-del",
            ownerId = "guest",
            sessionId = "sess-soft-del-solves",
            event = "3x3",
            durationMs = 8000L,
            penalty = "none",
            solvedAt = "2026-09-11T10:02:00.000Z",
            deletedAt = "2026-09-11T10:05:00.000Z"
        )
        solveDao.insertAll(listOf(activeSolve, deletedSolve))

        val stats = sessionDao.observeSessionsWithStats("guest", event = "3x3").first()[0]
        assertEquals(1, stats.solveCount)
        assertEquals(15000L, stats.bestDurationMs)
        assertEquals(15000L, stats.avgDurationMs)
    }

    @Test
    fun testSoftDeletedSessionExcludedFromEmittedList() = runTest {
        val activeSession = SessionEntity(
            id = "sess-act",
            ownerId = "guest",
            name = "Active Session",
            event = "3x3",
            startedAt = "2026-09-11T10:00:00.000Z"
        )
        val deletedSession = SessionEntity(
            id = "sess-del",
            ownerId = "guest",
            name = "Deleted Session",
            event = "3x3",
            startedAt = "2026-09-11T10:05:00.000Z",
            deletedAt = "2026-09-11T10:10:00.000Z"
        )
        sessionDao.insertAll(listOf(activeSession, deletedSession))

        val list = sessionDao.observeSessionsWithStats("guest", event = "3x3").first()
        assertEquals(1, list.size)
        assertEquals("sess-act", list[0].session.id)
    }

    @Test
    fun testFilteringByEventAndKind() = runTest {
        val s1 = SessionEntity(id = "s1", ownerId = "u1", name = "3x3 Auto", event = "3x3", kind = "automatic", startedAt = "2026-09-11T10:00:00.000Z")
        val s2 = SessionEntity(id = "s2", ownerId = "u1", name = "3x3 Manual", event = "3x3", kind = "manual", startedAt = "2026-09-11T10:01:00.000Z")
        val s3 = SessionEntity(id = "s3", ownerId = "u1", name = "4x4 Manual", event = "4x4", kind = "manual", startedAt = "2026-09-11T10:02:00.000Z")
        sessionDao.insertAll(listOf(s1, s2, s3))

        // event = "3x3", kind = null -> returns s1, s2
        val eventOnly = sessionDao.observeSessionsWithStats("u1", event = "3x3", kind = null).first()
        assertEquals(2, eventOnly.size)

        // event = null, kind = "manual" -> returns s2, s3
        val kindOnly = sessionDao.observeSessionsWithStats("u1", event = null, kind = "manual").first()
        assertEquals(2, kindOnly.size)

        // event = "3x3", kind = "manual" -> returns s2
        val eventAndKind = sessionDao.observeSessionsWithStats("u1", event = "3x3", kind = "manual").first()
        assertEquals(1, eventAndKind.size)
        assertEquals("s2", eventAndKind[0].session.id)

        // event = null, kind = null -> returns all 3
        val all = sessionDao.observeSessionsWithStats("u1", event = null, kind = null).first()
        assertEquals(3, all.size)
    }

    @Test
    fun testInvalidationTracking_insertingSolveOrUpdatingPenaltyEmitsLiveUpdates() = runTest {
        val session = SessionEntity(
            id = "sess-invalidation",
            ownerId = "guest",
            name = "Live Session",
            event = "3x3",
            startedAt = "2026-09-11T10:00:00.000Z"
        )
        sessionDao.insert(session)

        sessionDao.observeSessionsWithStats("guest", event = "3x3").test {
            // Initial emission: 0 solves
            val initial = awaitItem()
            assertEquals(1, initial.size)
            assertEquals(0, initial[0].solveCount)
            assertNull(initial[0].bestDurationMs)

            // Step 1: Add a solve (10000ms, none)
            val solve1 = SolveEntity(
                id = "sol-inv-1",
                ownerId = "guest",
                sessionId = "sess-invalidation",
                event = "3x3",
                durationMs = 10000L,
                penalty = "none",
                solvedAt = "2026-09-11T10:01:00.000Z"
            )
            solveDao.insert(solve1)
            val afterInsert1 = awaitItem()
            assertEquals(1, afterInsert1[0].solveCount)
            assertEquals(10000L, afterInsert1[0].bestDurationMs)
            assertEquals(10000L, afterInsert1[0].avgDurationMs)

            // Step 2: Add second solve (8000ms with +2 -> 10000ms effective)
            val solve2 = SolveEntity(
                id = "sol-inv-2",
                ownerId = "guest",
                sessionId = "sess-invalidation",
                event = "3x3",
                durationMs = 8000L,
                penalty = "plus_two",
                solvedAt = "2026-09-11T10:02:00.000Z"
            )
            solveDao.insert(solve2)
            val afterInsert2 = awaitItem()
            assertEquals(2, afterInsert2[0].solveCount)
            assertEquals(10000L, afterInsert2[0].bestDurationMs)
            assertEquals(10000L, afterInsert2[0].avgDurationMs)

            // Step 3: Update second solve penalty from +2 to clean (8000ms) -> best should update to 8000ms!
            solveDao.update(solve2.copy(penalty = "none"))
            val afterPenaltyUpdate = awaitItem()
            assertEquals(2, afterPenaltyUpdate[0].solveCount)
            assertEquals(8000L, afterPenaltyUpdate[0].bestDurationMs)
            assertEquals(9000L, afterPenaltyUpdate[0].avgDurationMs) // (10000 + 8000)/2 = 9000

            // Step 4: Soft-delete second solve -> stats revert to only solve 1
            solveDao.softDelete("sol-inv-2", "2026-09-11T10:05:00.000Z", "2026-09-11T10:05:00.000Z")
            val afterDelete = awaitItem()
            assertEquals(1, afterDelete[0].solveCount)
            assertEquals(10000L, afterDelete[0].bestDurationMs)
            assertEquals(10000L, afterDelete[0].avgDurationMs)

            cancelAndIgnoreRemainingEvents()
        }
    }
}
