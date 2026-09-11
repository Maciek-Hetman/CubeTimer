package com.maciekhetman.cubetimer.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.maciekhetman.cubetimer.data.SolvesRepository
import com.maciekhetman.cubetimer.data.local.dao.SolveDao
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Penalty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class Milestone3It2EmpiricalChallengeTest {

    private lateinit var context: Context
    private lateinit var testDispatcher: TestDispatcher
    private lateinit var database: CubeDatabase
    private lateinit var solveDao: SolveDao
    private lateinit var solvesRepository: SolvesRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)

        val directExecutor = java.util.concurrent.Executor { it.run() }
        database = CubeDatabase.createInMemory(
            context = context,
            queryExecutor = directExecutor,
            transactionExecutor = directExecutor
        )
        solveDao = database.solveDao()
        solvesRepository = SolvesRepository(
            context = context,
            solveDao = solveDao,
            sessionDao = database.sessionDao(),
            syncOutboxDao = database.syncOutboxDao(),
            database = database,
            ioDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    // =========================================================================
    // TASK 1: EMPIRICAL VERIFICATION OF PRIOR BEST DURATION CALCULATION
    // ACROSS TIMESTAMP REPRESENTATIONS (.000Z, without millis, identical, excludeSolveId)
    // =========================================================================

    @Test
    fun testExcludeSolveIdPreventsSelfMatchingForSingleSolve() = runTest(testDispatcher) {
        val formats = listOf(
            "2026-08-30T10:00:00.000Z",
            "2026-08-30T10:00:00Z",
            "2026-08-30T10:00:00.123Z"
        )

        for ((index, storedFormat) in formats.withIndex()) {
            val solveId = "single-solve-$index"
            val solve = SolveEntity(
                id = solveId,
                ownerId = "guest",
                event = "3x3",
                durationMs = 12500L,
                penalty = "none",
                solvedAt = storedFormat,
                scramble = "R U R' U'",
                version = 0L
            )
            solveDao.insert(solve)

            for (queryFormat in formats) {
                // With excludeSolveId, it must NEVER match itself regardless of string comparison artifact
                val priorBest = solveDao.getPriorBestSolveDuration(
                    ownerId = "guest",
                    event = "3x3",
                    solvedAt = queryFormat,
                    excludeSolveId = solveId
                )
                assertNull(
                    "Prior best for single solve must be null with excludeSolveId for stored=$storedFormat, query=$queryFormat",
                    priorBest
                )
            }

            // Cleanup for next format test
            solveDao.delete(solve)
        }
    }

    @Test
    fun testPriorBestDurationAcrossVariousTimestampRepresentations() = runTest(testDispatcher) {
        // Solve 1: 15.00s at 10:00:00Z (without millis)
        val s1 = SolveEntity(
            id = "solve-1",
            ownerId = "guest",
            event = "3x3",
            durationMs = 15000L,
            penalty = "none",
            solvedAt = "2026-08-30T10:00:00Z",
            scramble = "R U",
            version = 0L
        )
        // Solve 2: 12.00s at 10:05:00.000Z (with .000Z)
        val s2 = SolveEntity(
            id = "solve-2",
            ownerId = "guest",
            event = "3x3",
            durationMs = 12000L,
            penalty = "none",
            solvedAt = "2026-08-30T10:05:00.000Z",
            scramble = "U R",
            version = 0L
        )
        // Solve 3: 10.00s (+2 = 12.00s effective) at 10:10:00.500Z (with non-zero millis)
        val s3 = SolveEntity(
            id = "solve-3",
            ownerId = "guest",
            event = "3x3",
            durationMs = 10000L,
            penalty = "plus_two",
            solvedAt = "2026-08-30T10:10:00.500Z",
            scramble = "R' U'",
            version = 0L
        )
        // Solve 4: 9.00s at 10:15:00Z
        val s4 = SolveEntity(
            id = "solve-4",
            ownerId = "guest",
            event = "3x3",
            durationMs = 9000L,
            penalty = "none",
            solvedAt = "2026-08-30T10:15:00Z",
            scramble = "F R U",
            version = 0L
        )

        solveDao.insertAll(listOf(s1, s2, s3, s4))

        // Query before s1 -> null
        val pbBeforeS1 = solveDao.getPriorBestSolveDuration("guest", "3x3", "2026-08-30T09:59:59Z", s1.id)
        assertNull(pbBeforeS1)

        // Query at s2 (10:05:00Z or 10:05:00.000Z) -> should see s1 (15000L)
        val pbAtS2WithMillis = solveDao.getPriorBestSolveDuration("guest", "3x3", "2026-08-30T10:05:00.000Z", s2.id)
        assertEquals(15000L, pbAtS2WithMillis)

        val pbAtS2NoMillis = solveDao.getPriorBestSolveDuration("guest", "3x3", "2026-08-30T10:05:00Z", s2.id)
        assertEquals(15000L, pbAtS2NoMillis)

        // Query at s3 (10:10:00.500Z) -> should see best of s1 (15000) and s2 (12000) -> 12000L
        val pbAtS3 = solveDao.getPriorBestSolveDuration("guest", "3x3", "2026-08-30T10:10:00.500Z", s3.id)
        assertEquals(12000L, pbAtS3)

        // Query at s4 (10:15:00Z) -> should see best of s1 (15000), s2 (12000), s3 (10000+2000=12000) -> 12000L
        val pbAtS4 = solveDao.getPriorBestSolveDuration("guest", "3x3", "2026-08-30T10:15:00Z", s4.id)
        assertEquals(12000L, pbAtS4)

        // Query after s4 -> should see s4 (9000L)
        val pbAfterS4 = solveDao.getPriorBestSolveDuration("guest", "3x3", "2026-08-30T10:20:00Z", null)
        assertEquals(9000L, pbAfterS4)
    }

    @Test
    fun testPriorBestIdenticalTimestampsStrictInequality() = runTest(testDispatcher) {
        // Two solves at the exact same timestamp representation
        val sA = SolveEntity(
            id = "solve-identical-A",
            ownerId = "guest",
            event = "3x3",
            durationMs = 15000L,
            penalty = "none",
            solvedAt = "2026-08-30T12:00:00.000Z",
            scramble = "R U",
            version = 0L
        )
        val sB = SolveEntity(
            id = "solve-identical-B",
            ownerId = "guest",
            event = "3x3",
            durationMs = 11000L,
            penalty = "none",
            solvedAt = "2026-08-30T12:00:00.000Z",
            scramble = "U R",
            version = 0L
        )
        solveDao.insertAll(listOf(sA, sB))

        // When querying for prior best at "2026-08-30T12:00:00.000Z" (the timestamp of both solves):
        // Since SQL is solved_at < :solvedAt (strict inequality), neither solve is strictly prior to 12:00:00.000Z
        val pbForA = solveDao.getPriorBestSolveDuration("guest", "3x3", sA.solvedAt, excludeSolveId = sA.id)
        assertNull("Neither solve occurred strictly before 12:00:00.000Z", pbForA)

        val pbForB = solveDao.getPriorBestSolveDuration("guest", "3x3", sB.solvedAt, excludeSolveId = sB.id)
        assertNull("Neither solve occurred strictly before 12:00:00.000Z", pbForB)

        // For a later solve, both sA and sB are prior, so best is sB (11000)
        val pbLater = solveDao.getPriorBestSolveDuration("guest", "3x3", "2026-08-30T12:01:00.000Z", excludeSolveId = "new-solve")
        assertEquals(11000L, pbLater)
    }

    @Test
    fun testPriorBestExcludesDnfAndSoftDeletedSolves() = runTest(testDispatcher) {
        val sDnf = SolveEntity(
            id = "solve-dnf",
            ownerId = "guest",
            event = "3x3",
            durationMs = 8000L,
            penalty = "dnf",
            solvedAt = "2026-08-30T10:00:00Z",
            scramble = "R U",
            version = 0L
        )
        val sDeleted = SolveEntity(
            id = "solve-deleted",
            ownerId = "guest",
            event = "3x3",
            durationMs = 7000L,
            penalty = "none",
            solvedAt = "2026-08-30T10:01:00Z",
            deletedAt = "2026-08-30T10:05:00Z",
            scramble = "R U",
            version = 0L
        )
        val sValid = SolveEntity(
            id = "solve-valid",
            ownerId = "guest",
            event = "3x3",
            durationMs = 13000L,
            penalty = "none",
            solvedAt = "2026-08-30T10:02:00Z",
            scramble = "R U",
            version = 0L
        )
        solveDao.insertAll(listOf(sDnf, sDeleted, sValid))

        val priorBest = solveDao.getPriorBestSolveDuration(
            ownerId = "guest",
            event = "3x3",
            solvedAt = "2026-08-30T10:10:00Z",
            excludeSolveId = null
        )
        assertEquals("DNF (8000L) and Soft-deleted (7000L) must be excluded; best valid is 13000L", 13000L, priorBest)
    }

    // =========================================================================
    // TASK 2: RAPID CONCURRENT INSERTIONS ACROSS MULTIPLE SESSIONS
    // STRESS-TESTING CHUNKED PAGINATION & REACTIVE COUNT FLOWS
    // =========================================================================

    @Test
    fun testRapidConcurrentInsertionsAcrossMultipleSessionsWithPaginationAndReactiveCounts() = runTest(testDispatcher) {
        val session1 = "session-concurrent-1"
        val session2 = "session-concurrent-2"
        val session3 = "session-concurrent-3"

        val nowIso = Instant.parse("2026-08-30T00:00:00Z").toString()
        val sessionDao = database.sessionDao()
        sessionDao.insert(com.maciekhetman.cubetimer.data.local.entity.SessionEntity(
            id = session1,
            ownerId = "guest",
            name = "Session 1",
            event = "3x3",
            kind = "manual",
            startedAt = nowIso
        ))
        sessionDao.insert(com.maciekhetman.cubetimer.data.local.entity.SessionEntity(
            id = session2,
            ownerId = "guest",
            name = "Session 2",
            event = "3x3",
            kind = "manual",
            startedAt = nowIso
        ))
        sessionDao.insert(com.maciekhetman.cubetimer.data.local.entity.SessionEntity(
            id = session3,
            ownerId = "guest",
            name = "Session 3",
            event = "3x3",
            kind = "manual",
            startedAt = nowIso
        ))

        val countFlow1 = solvesRepository.observeSolveCountBySession(session1, "guest")
        val countFlow2 = solvesRepository.observeSolveCountBySession(session2, "guest")
        val countFlowAll = solvesRepository.observeAllSolvesCount("guest")

        // Track reactive emissions
        val collectedSession1 = mutableListOf<Int>()
        val collectedSession2 = mutableListOf<Int>()
        val collectedAll = mutableListOf<Int>()

        val job1 = launch { countFlow1.collect { collectedSession1.add(it) } }
        val job2 = launch { countFlow2.collect { collectedSession2.add(it) } }
        val jobAll = launch { countFlowAll.collect { collectedAll.add(it) } }

        advanceUntilIdle()

        assertEquals(listOf(0), collectedSession1)
        assertEquals(listOf(0), collectedSession2)
        assertEquals(listOf(0), collectedAll)

        // Concurrently insert solves into session1 (60 solves), session2 (40 solves), session3 (30 solves)
        // Total = 130 solves across 3 sessions
        val baseInstant = Instant.parse("2026-08-30T00:00:00Z")

        val inserter1 = async {
            for (i in 0 until 60) {
                val entity = SolveEntity(
                    id = "s1-$i",
                    ownerId = "guest",
                    sessionId = session1,
                    event = "3x3",
                    durationMs = 10000L + i,
                    penalty = "none",
                    solvedAt = baseInstant.plusSeconds(i.toLong()).toString(),
                    scramble = "R U",
                    version = 0L
                )
                solveDao.insert(entity)
            }
        }

        val inserter2 = async {
            for (i in 0 until 40) {
                val entity = SolveEntity(
                    id = "s2-$i",
                    ownerId = "guest",
                    sessionId = session2,
                    event = "3x3",
                    durationMs = 12000L + i,
                    penalty = "none",
                    solvedAt = baseInstant.plusSeconds(100L + i).toString(),
                    scramble = "U R",
                    version = 0L
                )
                solveDao.insert(entity)
            }
        }

        val inserter3 = async {
            for (i in 0 until 30) {
                val entity = SolveEntity(
                    id = "s3-$i",
                    ownerId = "guest",
                    sessionId = session3,
                    event = "3x3",
                    durationMs = 14000L + i,
                    penalty = "none",
                    solvedAt = baseInstant.plusSeconds(200L + i).toString(),
                    scramble = "F R",
                    version = 0L
                )
                solveDao.insert(entity)
            }
        }

        awaitAll(inserter1, inserter2, inserter3)
        advanceUntilIdle()

        // 1. Verify final reactive counts
        assertEquals(60, collectedSession1.last())
        assertEquals(40, collectedSession2.last())
        assertEquals(130, collectedAll.last())

        // Verify distinct monotonicity (no duplicate consecutive emissions due to distinctUntilChanged)
        for (i in 0 until collectedSession1.size - 1) {
            assertTrue("Emissions must be strictly increasing: ${collectedSession1[i]} < ${collectedSession1[i+1]}",
                collectedSession1[i] < collectedSession1[i+1])
        }
        for (i in 0 until collectedSession2.size - 1) {
            assertTrue("Emissions must be strictly increasing: ${collectedSession2[i]} < ${collectedSession2[i+1]}",
                collectedSession2[i] < collectedSession2[i+1])
        }
        for (i in 0 until collectedAll.size - 1) {
            assertTrue("Emissions must be strictly increasing: ${collectedAll[i]} < ${collectedAll[i+1]}",
                collectedAll[i] < collectedAll[i+1])
        }

        // 2. Empirically verify chunked pagination for session 1 (60 items, page size 50)
        val page1 = solveDao.getSolvesPagedBySession("guest", session1, limit = 50, offset = 0)
        val page2 = solveDao.getSolvesPagedBySession("guest", session1, limit = 50, offset = 50)
        val page3 = solveDao.getSolvesPagedBySession("guest", session1, limit = 50, offset = 100)

        assertEquals(50, page1.size)
        assertEquals(10, page2.size)
        assertEquals(0, page3.size)

        // Ensure no overlap between page 1 and page 2
        val page1Ids = page1.map { it.id }.toSet()
        val page2Ids = page2.map { it.id }.toSet()
        assertTrue("No overlapping IDs between pages", page1Ids.intersect(page2Ids).isEmpty())
        assertEquals(60, page1Ids.size + page2Ids.size)

        // 3. Empirically verify chunked pagination for all solves (130 items, page size 50)
        val allP1 = solveDao.getAllSolvesPaged("guest", limit = 50, offset = 0)
        val allP2 = solveDao.getAllSolvesPaged("guest", limit = 50, offset = 50)
        val allP3 = solveDao.getAllSolvesPaged("guest", limit = 50, offset = 100)
        val allP4 = solveDao.getAllSolvesPaged("guest", limit = 50, offset = 150)

        assertEquals(50, allP1.size)
        assertEquals(50, allP2.size)
        assertEquals(30, allP3.size)
        assertEquals(0, allP4.size)

        val allIds = (allP1 + allP2 + allP3).map { it.id }.toSet()
        assertEquals(130, allIds.size)

        job1.cancel()
        job2.cancel()
        jobAll.cancel()
    }
}
