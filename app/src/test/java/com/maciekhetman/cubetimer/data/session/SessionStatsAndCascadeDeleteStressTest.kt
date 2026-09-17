package com.maciekhetman.cubetimer.data.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.local.dao.SessionDao
import com.maciekhetman.cubetimer.data.local.dao.SolveDao
import com.maciekhetman.cubetimer.data.local.dao.SyncOutboxDao
import com.maciekhetman.cubetimer.data.local.entity.SessionEntity
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import com.maciekhetman.cubetimer.data.remote.NetworkModule
import com.maciekhetman.cubetimer.data.remote.dto.SessionSyncPayload
import com.maciekhetman.cubetimer.data.remote.dto.SolveSyncPayload
import com.maciekhetman.cubetimer.data.local.mapper.toDomain
import com.maciekhetman.cubetimer.data.local.mapper.toSolveTime
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Penalty
import com.maciekhetman.cubetimer.model.SolveTime
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
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
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.system.measureTimeMillis

/**
 * Adversarial empirical stress testing for Milestone 1:
 * 1. observeSessionsWithStats:
 *    - Concurrent mutations (inserts, updates, deletes across coroutines)
 *    - 1000 solves stress test (performance and exact mathematical oracle calculation)
 *    - All DNF solves (count matches, best & avg must be null)
 *    - +2 penalty behavior (affects best single and average duration properly, with rounding)
 * 2. deleteSessionWithSolves and restoreSessionWithSolves:
 *    - Non-existent sessionId handling
 *    - Zero solves session handling
 *    - Double deletion idempotency
 *    - Outbox mutation sequencing (session upsert precedes solve upserts, session delete precedes solve deletes)
 *    - 1000 solves cascade delete and restore
 */
@RunWith(RobolectricTestRunner::class)
class SessionStatsAndCascadeDeleteStressTest {

    private lateinit var context: Context
    private lateinit var database: CubeDatabase
    private lateinit var sessionDao: SessionDao
    private lateinit var solveDao: SolveDao
    private lateinit var syncOutboxDao: SyncOutboxDao
    private lateinit var repository: SessionRepositoryImpl
    private var syncTriggerCount = 0

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = CubeDatabase.createInMemory(context)
        sessionDao = database.sessionDao()
        solveDao = database.solveDao()
        syncOutboxDao = database.syncOutboxDao()
        syncTriggerCount = 0
        repository = SessionRepositoryImpl(
            database = database,
            sessionDao = sessionDao,
            syncOutboxDao = syncOutboxDao,
            solveDao = solveDao,
            syncTrigger = { syncTriggerCount++ }
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    // =========================================================================
    // 1. observeSessionsWithStats CHALLENGES
    // =========================================================================

    @Test
    fun testObserveSessionsWithStats_concurrentInsertsDeletesUpdates_convergesConsistently() = runTest {
        val sessionId = "sess-concurrent"
        val ownerId = "user-concurrent"
        val session = SessionEntity(
            id = sessionId,
            ownerId = ownerId,
            name = "Concurrent Session",
            event = "3x3",
            kind = "manual",
            startedAt = "2026-09-11T12:00:00.000Z"
        )
        sessionDao.insert(session)

        // Seed 10 initial solves
        val initialSolves = (1..10).map { i ->
            SolveEntity(
                id = "init-solve-$i",
                ownerId = ownerId,
                sessionId = sessionId,
                event = "3x3",
                durationMs = 10000L + i * 500,
                penalty = "none",
                solvedAt = "2026-09-11T12:00:0${i}.000Z"
            )
        }
        solveDao.insertAll(initialSolves)

        // Run 10 concurrent coroutines performing diverse mutations simultaneously
        coroutineScope {
            val jobs = (1..10).map { workerIdx ->
                async {
                    when (workerIdx % 4) {
                        0 -> {
                            // Insert 5 new solves with various penalties
                            for (j in 1..5) {
                                solveDao.insert(
                                    SolveEntity(
                                        id = "worker-$workerIdx-solve-$j",
                                        ownerId = ownerId,
                                        sessionId = sessionId,
                                        event = "3x3",
                                        durationMs = 9000L + (workerIdx * 100) + j * 50,
                                        penalty = if (j % 2 == 0) "plus_two" else "none",
                                        solvedAt = "2026-09-11T12:01:${workerIdx}${j}.000Z"
                                    )
                                )
                            }
                        }
                        1 -> {
                            // Update penalty of initial solves
                            for (j in 1..5) {
                                val target = initialSolves[j - 1]
                                solveDao.update(target.copy(penalty = "plus_two", updatedAt = "2026-09-11T12:02:00.000Z"))
                            }
                        }
                        2 -> {
                            // Soft-delete some initial solves
                            for (j in 6..8) {
                                solveDao.softDelete(initialSolves[j - 1].id, "2026-09-11T12:03:00.000Z", "2026-09-11T12:03:00.000Z")
                            }
                        }
                        3 -> {
                            // Add DNF solves
                            for (j in 1..3) {
                                solveDao.insert(
                                    SolveEntity(
                                        id = "dnf-$workerIdx-$j",
                                        ownerId = ownerId,
                                        sessionId = sessionId,
                                        event = "3x3",
                                        durationMs = 6000L, // very fast, but DNF!
                                        penalty = "dnf",
                                        solvedAt = "2026-09-11T12:04:00.000Z"
                                    )
                                )
                            }
                        }
                    }
                }
            }
            jobs.awaitAll()
        }

        // Query the final state through observeSessionsWithStats Flow
        val statsList = sessionDao.observeSessionsWithStats(ownerId, event = "3x3").first()
        assertEquals(1, statsList.size)
        val stats = statsList[0]

        // Calculate expected values directly from non-deleted solves in DB
        val activeSolves = solveDao.getSolvesBySession(ownerId, sessionId)
        assertEquals("Total active solves count must match exactly", activeSolves.size, stats.solveCount)

        val nonDnfDurations = activeSolves
            .filter { it.penalty != "dnf" }
            .map { solve ->
                if (solve.penalty == "plus_two") solve.durationMs + 2000L else solve.durationMs
            }

        if (nonDnfDurations.isEmpty()) {
            assertNull(stats.bestDurationMs)
            assertNull(stats.avgDurationMs)
        } else {
            val expectedBest = nonDnfDurations.minOrNull()
            val expectedAvg = nonDnfDurations.average().roundToInt().toLong()
            assertEquals("Best duration must match ground truth", expectedBest, stats.bestDurationMs)
            assertEquals("Average duration must match ground truth", expectedAvg, stats.avgDurationMs)
        }
    }

    @Test
    fun testObserveSessionsWithStats_sessionWith1000Solves_performanceAndExactCalculations() = runTest {
        val sessionId = "sess-1000"
        val ownerId = "perf-owner"
        val session = SessionEntity(
            id = sessionId,
            ownerId = ownerId,
            name = "1000 Solves Session",
            event = "3x3",
            startedAt = "2026-09-11T10:00:00.000Z"
        )
        sessionDao.insert(session)

        val random = Random(42)
        val solveEntities = ArrayList<SolveEntity>(1000)
        var oracleBest: Long? = null
        var oracleNonDnfSum = 0L
        var oracleNonDnfCount = 0

        for (i in 1..1000) {
            val isDnf = i % 10 == 0 // 100 DNF solves
            val isPlusTwo = !isDnf && (i % 5 == 0) // 180 +2 solves
            val rawTime = random.nextLong(7500L, 25000L)
            val penalty = when {
                isDnf -> "dnf"
                isPlusTwo -> "plus_two"
                else -> "none"
            }
            val effectiveTime = when {
                isDnf -> null
                isPlusTwo -> rawTime + 2000L
                else -> rawTime
            }

            if (effectiveTime != null) {
                oracleNonDnfSum += effectiveTime
                oracleNonDnfCount++
                if (oracleBest == null || effectiveTime < oracleBest) {
                    oracleBest = effectiveTime
                }
            }

            solveEntities.add(
                SolveEntity(
                    id = "solve-1000-$i",
                    ownerId = ownerId,
                    sessionId = sessionId,
                    event = "3x3",
                    durationMs = rawTime,
                    penalty = penalty,
                    solvedAt = "2026-09-11T10:%02d:%02d.000Z".format(i / 60 % 60, i % 60)
                )
            )
        }

        // Insert in batches of 200 to test batching
        solveEntities.chunked(200).forEach { batch ->
            solveDao.insertAll(batch)
        }

        // Measure query execution time
        val statsList: List<com.maciekhetman.cubetimer.data.local.dto.SessionWithStats>
        val queryDurationMs = measureTimeMillis {
            statsList = sessionDao.observeSessionsWithStats(ownerId, event = "3x3").first()
        }

        println("observeSessionsWithStats with 1000 solves executed in ${queryDurationMs}ms")
        assertTrue("Query for 1000 solves should execute within 500ms in SQLite", queryDurationMs < 500L)

        assertEquals(1, statsList.size)
        val stats = statsList[0]

        // Exact assertions against ground-truth mathematical oracle
        assertEquals("Solve count must be exactly 1000", 1000, stats.solveCount)
        assertNotNull("Best duration must not be null", stats.bestDurationMs)
        assertEquals("Best single must match exact oracle minimum", oracleBest, stats.bestDurationMs)

        val oracleAvg = (oracleNonDnfSum.toDouble() / oracleNonDnfCount).roundToInt().toLong()
        assertNotNull("Average duration must not be null", stats.avgDurationMs)
        assertEquals("Average duration must match exact oracle rounded mean", oracleAvg, stats.avgDurationMs)
    }

    @Test
    fun testObserveSessionsWithStats_allSolvesAreDnf_emitsCountNAndNullStats() = runTest {
        val sessionId = "sess-all-dnf-stress"
        val ownerId = "dnf-owner"
        sessionDao.insert(
            SessionEntity(
                id = sessionId,
                ownerId = ownerId,
                name = "All DNF Stress",
                event = "3x3",
                startedAt = "2026-09-11T10:00:00.000Z"
            )
        )

        // 25 solves, all DNF with varying raw times (some very fast!)
        val dnfSolves = (1..25).map { i ->
            SolveEntity(
                id = "dnf-stress-$i",
                ownerId = ownerId,
                sessionId = sessionId,
                event = "3x3",
                durationMs = 5000L + i * 100,
                penalty = "dnf",
                solvedAt = "2026-09-11T10:01:00.000Z"
            )
        }
        solveDao.insertAll(dnfSolves)

        val stats = sessionDao.observeSessionsWithStats(ownerId, event = "3x3").first()[0]
        assertEquals("Solve count must include all 25 DNF solves", 25, stats.solveCount)
        assertNull("Best duration must be null when all solves are DNF", stats.bestDurationMs)
        assertNull("Average duration must be null when all solves are DNF", stats.avgDurationMs)
    }

    @Test
    fun testObserveSessionsWithStats_plusTwoPenaltyCorrectlyAffectsBothBestAndAverage() = runTest {
        val sessionId = "sess-plus-two"
        val ownerId = "plus-two-owner"
        sessionDao.insert(
            SessionEntity(
                id = sessionId,
                ownerId = ownerId,
                name = "Plus Two Tests",
                event = "3x3",
                startedAt = "2026-09-11T10:00:00.000Z"
            )
        )

        // Case 1: Solve A has raw 9.0s with +2 -> effective 11.0s. Solve B has clean 10.0s.
        // Best MUST be 10.0s (Solve B wins because 10.0s < 11.0s)
        val sA = SolveEntity(id = "s-a", ownerId = ownerId, sessionId = sessionId, event = "3x3", durationMs = 9000L, penalty = "plus_two", solvedAt = "2026-09-11T10:01:00.000Z")
        val sB = SolveEntity(id = "s-b", ownerId = ownerId, sessionId = sessionId, event = "3x3", durationMs = 10000L, penalty = "none", solvedAt = "2026-09-11T10:02:00.000Z")
        solveDao.insertAll(listOf(sA, sB))

        val stats1 = sessionDao.observeSessionsWithStats(ownerId, event = "3x3").first()[0]
        assertEquals(2, stats1.solveCount)
        assertEquals("Best single must be 10000ms (10.0s clean beat 9.0s + 2s)", 10000L, stats1.bestDurationMs)
        assertEquals("Average must be (11000 + 10000) / 2 = 10500ms", 10500L, stats1.avgDurationMs)

        // Case 2: Solve C has raw 7.0s with +2 -> effective 9.0s.
        // Best MUST now be 9000ms (Solve C wins because 9.0s < 10.0s)
        val sC = SolveEntity(id = "s-c", ownerId = ownerId, sessionId = sessionId, event = "3x3", durationMs = 7000L, penalty = "plus_two", solvedAt = "2026-09-11T10:03:00.000Z")
        solveDao.insert(sC)

        val stats2 = sessionDao.observeSessionsWithStats(ownerId, event = "3x3").first()[0]
        assertEquals(3, stats2.solveCount)
        assertEquals("Best single must now be 9000ms (7.0s + 2s beat 10.0s)", 9000L, stats2.bestDurationMs)
        // Average: (11000 + 10000 + 9000) / 3 = 30000 / 3 = 10000ms
        assertEquals("Average must be 10000ms", 10000L, stats2.avgDurationMs)

        // Case 3: Add DNF solve D (5.0s raw). Best and average MUST NOT change!
        val sD = SolveEntity(id = "s-d", ownerId = ownerId, sessionId = sessionId, event = "3x3", durationMs = 5000L, penalty = "dnf", solvedAt = "2026-09-11T10:04:00.000Z")
        solveDao.insert(sD)

        val stats3 = sessionDao.observeSessionsWithStats(ownerId, event = "3x3").first()[0]
        assertEquals(4, stats3.solveCount)
        assertEquals("Best single must remain 9000ms", 9000L, stats3.bestDurationMs)
        assertEquals("Average must remain 10000ms ignoring DNF", 10000L, stats3.avgDurationMs)
    }

    // =========================================================================
    // 2. deleteSessionWithSolves and restoreSessionWithSolves CHALLENGES
    // =========================================================================

    @Test
    fun testDeleteSessionWithSolves_nonExistentSession_returnsNullAndLeavesDbUntouched() = runTest {
        val result = repository.deleteSessionWithSolves("non-existent-uuid-999", ownerId = "guest")
        assertNull("deleteSessionWithSolves for non-existent session must return null", result)

        val authResult = repository.deleteSessionWithSolves("non-existent-uuid-999", ownerId = "auth-user")
        assertNull(authResult)
        assertEquals("No outbox mutations should be enqueued", 0, syncOutboxDao.countPending("auth-user"))
    }

    @Test
    fun testDeleteSessionWithSolves_sessionWithZeroSolves_handledCleanly() = runTest {
        val session = repository.createManualSession("Zero Solves Session", Mode.CUBE_3x3, ownerId = "guest")

        // Cascade delete on session with 0 solves
        val snapshot = repository.deleteSessionWithSolves(session.id, ownerId = "guest")
        assertNotNull("Snapshot must not be null", snapshot)
        assertEquals(session.id, snapshot?.session?.id)
        assertTrue("Solves list in snapshot must be empty", snapshot?.solves?.isEmpty() == true)

        // Verify session is soft-deleted
        val sessionInDb = sessionDao.getSessionById(session.id)
        assertNotNull(sessionInDb?.deletedAt)

        // Restore zero-solves session
        repository.restoreSessionWithSolves(snapshot!!, ownerId = "guest")
        val restoredSession = sessionDao.getSessionById(session.id)
        assertNull("Restored session deletedAt must be null", restoredSession?.deletedAt)
        assertEquals(0, solveDao.getSolvesBySession("guest", session.id).size)
    }

    @Test
    fun testDeleteSessionWithSolves_doubleDelete_returnsNullOnSecondAttempt() = runTest {
        val session = repository.createManualSession("Double Delete Target", Mode.CUBE_3x3, ownerId = "guest")
        val solve = SolveEntity(
            id = "solve-dd-1",
            ownerId = "guest",
            sessionId = session.id,
            event = "3x3",
            durationMs = 12000L,
            penalty = "none",
            solvedAt = "2026-09-11T12:00:00.000Z"
        )
        solveDao.insert(solve)

        // First deletion succeeds
        val snapshot1 = repository.deleteSessionWithSolves(session.id, ownerId = "guest")
        assertNotNull("First deletion must succeed", snapshot1)
        assertEquals(1, snapshot1?.solves?.size)

        // Second deletion on already soft-deleted session must return null safely
        val snapshot2 = repository.deleteSessionWithSolves(session.id, ownerId = "guest")
        assertNull("Second deletion must return null without throwing", snapshot2)

        // Solves and session remain soft-deleted
        val inDb = sessionDao.getSessionById(session.id)
        assertNotNull(inDb?.deletedAt)
        val solvesInDb = solveDao.getAllSolvesForOwner("guest")
        assertEquals(1, solvesInDb.size)
        assertNotNull(solvesInDb[0].deletedAt)
    }

    @Test
    fun testOutboxMutations_sessionUpsertPrecedesSolveUpserts_andSessionDeletePrecedesSolveDeletes() = runTest {
        val userId = "user-outbox-seq"
        val session = repository.createManualSession("Sequencing Session", Mode.CUBE_3x3, ownerId = userId)

        val solves = (1..5).map { i ->
            SolveEntity(
                id = "s-seq-$i",
                ownerId = userId,
                sessionId = session.id,
                event = "3x3",
                durationMs = 10000L + i * 1000,
                penalty = "none",
                solvedAt = "2026-09-11T14:0$i:00.000Z"
            )
        }
        solveDao.insertAll(solves)

        // Clear outbox from setup
        syncOutboxDao.clearOutbox(userId)
        val triggersBeforeDelete = syncTriggerCount

        // 1. Execute cascade delete
        val snapshot = repository.deleteSessionWithSolves(session.id, ownerId = userId)
        assertNotNull(snapshot)
        assertTrue(syncTriggerCount > triggersBeforeDelete)

        // Verify Delete Outbox Ordering: session delete MUST be index 0
        val deleteMutations = syncOutboxDao.getPendingMutations(userId)
        assertEquals("Must have 1 session delete + 5 solve deletes = 6 mutations", 6, deleteMutations.size)

        val firstDelete = deleteMutations[0]
        assertEquals("First delete mutation MUST be 'session' to ensure proper sequence", "session", firstDelete.entityType)
        assertEquals("delete", firstDelete.action)
        assertEquals(session.id, firstDelete.entityId)

        for (i in 1..5) {
            val mutation = deleteMutations[i]
            assertEquals("solve", mutation.entityType)
            assertEquals("delete", mutation.action)
            assertEquals("s-seq-$i", mutation.entityId)
        }

        // 2. Clear outbox and execute restore
        syncOutboxDao.clearOutbox(userId)
        val triggersBeforeRestore = syncTriggerCount

        repository.restoreSessionWithSolves(snapshot!!, ownerId = userId)
        assertTrue(syncTriggerCount > triggersBeforeRestore)

        // Verify Restore Outbox Ordering: session upsert MUST precede solve upserts to prevent foreign key errors on server!
        val restoreMutations = syncOutboxDao.getPendingMutations(userId)
        assertEquals("Must have 1 session upsert + 5 solve upserts = 6 mutations", 6, restoreMutations.size)

        val firstRestore = restoreMutations[0]
        assertEquals("First restore mutation MUST be 'session' upsert (preceding solve upserts)!", "session", firstRestore.entityType)
        assertEquals("upsert", firstRestore.action)
        assertEquals(session.id, firstRestore.entityId)
        assertNotNull("Session payload must not be null", firstRestore.payloadJson)

        val sessionPayload = NetworkModule.json.decodeFromString<SessionSyncPayload>(firstRestore.payloadJson!!)
        assertEquals("Sequencing Session", sessionPayload.name)
        assertEquals("3x3", sessionPayload.event)

        for (i in 1..5) {
            val mutation = restoreMutations[i]
            assertEquals("solve", mutation.entityType)
            assertEquals("upsert", mutation.action)
            assertEquals("s-seq-$i", mutation.entityId)
            assertNotNull(mutation.payloadJson)

            val solvePayload = NetworkModule.json.decodeFromString<SolveSyncPayload>(mutation.payloadJson!!)
            assertEquals(session.id, solvePayload.sessionId)
            assertEquals(10000L + i * 1000, solvePayload.durationMs)
        }
    }

    @Test
    fun testLargeSessionDeleteAndRestore_1000Solves_cascadePerformanceAndIntegrity() = runTest {
        val sessionId = "sess-large-cascade"
        val ownerId = "large-cascade-user"
        sessionDao.insert(
            SessionEntity(
                id = sessionId,
                ownerId = ownerId,
                name = "1000 Solves Cascade",
                event = "3x3",
                startedAt = "2026-09-11T15:00:00.000Z"
            )
        )

        val solves = (1..1000).map { i ->
            SolveEntity(
                id = "sol-large-$i",
                ownerId = ownerId,
                sessionId = sessionId,
                event = "3x3",
                durationMs = 12000L,
                penalty = "none",
                solvedAt = "2026-09-11T15:%02d:%02d.000Z".format(i / 60 % 60, i % 60)
            )
        }
        solves.chunked(250).forEach { solveDao.insertAll(it) }

        // Cascade delete 1000 solves
        var snapshot: DeletedSessionSnapshot? = null
        val deleteDurationMs = measureTimeMillis {
            snapshot = repository.deleteSessionWithSolves(sessionId, ownerId = ownerId)
            assertNotNull(snapshot)
            assertEquals(1000, snapshot?.solves?.size)
        }
        println("deleteSessionWithSolves with 1000 solves completed in ${deleteDurationMs}ms")
        assertTrue("Cascade delete of 1000 solves should complete under 2500ms", deleteDurationMs < 2500L)

        // Verify all 1000 solves are marked deleted
        assertEquals(0, solveDao.getSolvesBySession(ownerId, sessionId).size)
        val allInDb = solveDao.getAllSolvesForOwner(ownerId)
        assertEquals(1000, allInDb.size)
        assertTrue(allInDb.all { it.deletedAt != null })

        // Cascade restore 1000 solves using returned snapshot
        val restoreDurationMs = measureTimeMillis {
            repository.restoreSessionWithSolves(snapshot!!, ownerId = ownerId)
        }
        println("restoreSessionWithSolves with 1000 solves completed in ${restoreDurationMs}ms")
        assertTrue("Cascade restore of 1000 solves should complete under 2500ms", restoreDurationMs < 2500L)

        // Verify all 1000 solves are restored
        val restoredSolves = solveDao.getSolvesBySession(ownerId, sessionId)
        assertEquals(1000, restoredSolves.size)
        assertTrue(restoredSolves.all { it.deletedAt == null })

        val restoredSession = sessionDao.getSessionById(sessionId)
        assertNull(restoredSession?.deletedAt)
    }
}
