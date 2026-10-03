package com.maciekhetman.cubetimer.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.maciekhetman.cubetimer.data.local.dao.SolveDao
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class Milestone3It2EmpiricalChallengeTest {

    private lateinit var context: Context
    private lateinit var testDispatcher: TestDispatcher
    private lateinit var database: CubeDatabase
    private lateinit var solveDao: SolveDao

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
            solveDao.softDelete(solveId, deletedAt = "2026-08-30T12:00:00.000Z", updatedAt = "2026-08-30T12:00:00.000Z")
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
}
