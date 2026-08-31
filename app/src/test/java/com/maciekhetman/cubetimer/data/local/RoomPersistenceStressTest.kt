package com.maciekhetman.cubetimer.data.local

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.maciekhetman.cubetimer.data.local.converter.CubeTypeConverters
import com.maciekhetman.cubetimer.data.local.dao.ConflictDao
import com.maciekhetman.cubetimer.data.local.dao.SessionDao
import com.maciekhetman.cubetimer.data.local.dao.SolveDao
import com.maciekhetman.cubetimer.data.local.dao.SyncMetadataDao
import com.maciekhetman.cubetimer.data.local.dao.SyncOutboxDao
import com.maciekhetman.cubetimer.data.local.entity.ConflictEntity
import com.maciekhetman.cubetimer.data.local.entity.SessionEntity
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import com.maciekhetman.cubetimer.data.local.entity.SyncMetadataEntity
import com.maciekhetman.cubetimer.data.local.entity.SyncOutboxEntity
import com.maciekhetman.cubetimer.data.local.migration.DataStoreMigration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
class RoomPersistenceStressTest {

    private lateinit var context: Context
    private lateinit var database: CubeDatabase
    private lateinit var solveDao: SolveDao
    private lateinit var sessionDao: SessionDao
    private lateinit var outboxDao: SyncOutboxDao
    private lateinit var metadataDao: SyncMetadataDao
    private lateinit var conflictDao: ConflictDao

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, CubeDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    db.execSQL("PRAGMA foreign_keys = ON;")
                }
            })
            .build()

        solveDao = database.solveDao()
        sessionDao = database.sessionDao()
        outboxDao = database.syncOutboxDao()
        metadataDao = database.syncMetadataDao()
        conflictDao = database.conflictDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    // =========================================================================
    // 1. FOREIGN KEY BEHAVIOR & INTEGRITY
    // =========================================================================

    @Test
    fun testForeignKeyCascadeSetNullOnSessionDeletionPreservesAllSolveData() = runBlocking {
        // Create 3 sessions
        val sessions = (1..3).map { i ->
            SessionEntity(
                id = "session-$i",
                ownerId = "user-1",
                name = "Session $i",
                event = "3x3",
                startedAt = "2026-08-30T10:0$i:00.000Z"
            )
        }
        sessionDao.insertAll(sessions)

        // Create 150 solves: 50 in session-1, 50 in session-2, 50 in session-3
        val solves = (1..150).map { i ->
            val sessId = "session-${((i - 1) / 50) + 1}"
            SolveEntity(
                id = "solve-$i",
                ownerId = "user-1",
                sessionId = sessId,
                event = "3x3",
                durationMs = 10000L + i,
                penalty = if (i % 3 == 0) "plus_two" else "none",
                solvedAt = String.format("2026-08-30T10:%02d:%02d.000Z", i / 60, i % 60),
                scramble = "R U R' U' #$i",
                version = 1L
            )
        }
        solveDao.insertAll(solves)

        // Verify initial association
        val initialSess1Solves = solveDao.getSolvesBySession("user-1", "session-1")
        assertEquals(50, initialSess1Solves.size)
        assertEquals(150, solveDao.getAllActiveSolvesForOwner("user-1").size)

        // Delete session-1
        sessionDao.deleteById("session-1")

        // Assert:
        // 1. Session is deleted
        assertNull(sessionDao.getSessionById("session-1"))
        assertNotNull(sessionDao.getSessionById("session-2"))
        assertNotNull(sessionDao.getSessionById("session-3"))

        // 2. Solves belonging to session-1 still exist, but have sessionId = null
        val allSolvesAfter = solveDao.getAllActiveSolvesForOwner("user-1")
        assertEquals("No solve should be lost when session is deleted", 150, allSolvesAfter.size)

        val unassignedSolves = allSolvesAfter.filter { it.sessionId == null }
        assertEquals(50, unassignedSolves.size)
        assertTrue(unassignedSolves.all { it.id.startsWith("solve-") && (it.id.removePrefix("solve-").toInt() <= 50) })

        // 3. Verify all other solve properties remained unaltered
        val solve1 = solveDao.getSolveById("solve-1")
        assertNotNull(solve1)
        assertNull(solve1?.sessionId)
        assertEquals(10001L, solve1?.durationMs)
        assertEquals("none", solve1?.penalty)
        assertEquals("R U R' U' #1", solve1?.scramble)
        assertEquals(1L, solve1?.version)

        // 4. Session 2 and Session 3 solves are unaffected
        assertEquals(50, solveDao.getSolvesBySession("user-1", "session-2").size)
        assertEquals(50, solveDao.getSolvesBySession("user-1", "session-3").size)
    }

    @Test
    fun testForeignKeyConstraintViolationOnInvalidSessionId() = runBlocking {
        // Attempting to insert a solve with a non-existent sessionId when PRAGMA foreign_keys = ON
        val invalidSolve = SolveEntity(
            id = "solve-fk-invalid",
            ownerId = "guest",
            sessionId = "non-existent-session-id",
            event = "3x3",
            durationMs = 9500L,
            solvedAt = "2026-08-30T10:00:00.000Z"
        )

        try {
            solveDao.insert(invalidSolve)
            fail("Expected SQLiteConstraintException due to foreign key violation on non-existent session_id")
        } catch (e: Exception) {
            var curr: Throwable? = e
            var foundConstraintException = false
            while (curr != null) {
                if (curr is SQLiteConstraintException || curr.message?.contains("FOREIGN KEY", ignoreCase = true) == true) {
                    foundConstraintException = true
                    break
                }
                curr = curr.cause
            }
            assertTrue("Expected SQLiteConstraintException for invalid foreign key, got: ${e.javaClass.name}: ${e.message}", foundConstraintException)
        }
    }

    // =========================================================================
    // 2. CONCURRENCY STRESS TESTS
    // =========================================================================

    @Test
    fun testConcurrentInsertsUpdatesAndQueriesAcrossMultipleCoroutines() = runBlocking(Dispatchers.IO) {
        val totalCoroutines = 20
        val itemsPerCoroutine = 50
        val totalSolves = totalCoroutines * itemsPerCoroutine

        // Step A: Concurrent Inserts
        val insertJobs = (0 until totalCoroutines).map { threadIdx ->
            async {
                val batch = (0 until itemsPerCoroutine).map { itemIdx ->
                    val idx = threadIdx * itemsPerCoroutine + itemIdx
                    SolveEntity(
                        id = "solve-concurrent-$idx",
                        ownerId = "stress-user",
                        sessionId = null,
                        event = if (idx % 2 == 0) "3x3" else "2x2",
                        durationMs = 10000L + idx,
                        penalty = "none",
                        solvedAt = String.format("2026-08-30T10:%02d:%02d.000Z", (idx / 60) % 60, idx % 60)
                    )
                }
                solveDao.insertAll(batch)
            }
        }
        insertJobs.awaitAll()

        // Verify total inserted
        val activeSolves = solveDao.getAllActiveSolvesForOwner("stress-user")
        assertEquals(totalSolves, activeSolves.size)

        // Step B: Concurrent Read-Modify-Write (Penalty updates & Soft deletes simultaneously)
        val updateJobs = (0 until totalCoroutines).map { threadIdx ->
            async {
                for (itemIdx in 0 until itemsPerCoroutine) {
                    val idx = threadIdx * itemsPerCoroutine + itemIdx
                    val solveId = "solve-concurrent-$idx"
                    if (idx % 4 == 0) {
                        // Soft delete
                        solveDao.softDelete(solveId, "2026-08-30T11:00:00.000Z", "2026-08-30T11:00:00.000Z")
                    } else if (idx % 2 == 0) {
                        // Update penalty
                        val existing = solveDao.getSolveById(solveId)
                        if (existing != null) {
                            solveDao.update(existing.copy(penalty = "plus_two", updatedAt = "2026-08-30T11:00:00.000Z"))
                        }
                    }
                }
            }
        }
        updateJobs.awaitAll()

        // Step C: Verify state integrity
        val remainingActive = solveDao.getAllActiveSolvesForOwner("stress-user")
        val expectedDeletedCount = totalSolves / 4
        val expectedRemainingActive = totalSolves - expectedDeletedCount
        assertEquals(expectedRemainingActive, remainingActive.size)

        val plusTwoCount = remainingActive.count { it.penalty == "plus_two" }
        val expectedPlusTwoCount = totalSolves / 4
        assertEquals(expectedPlusTwoCount, plusTwoCount)
    }

    @Test
    fun testConcurrentGuestAdoptionWithSimultaneousWrites() = runBlocking(Dispatchers.IO) {
        // Prepopulate 100 guest solves
        val initialGuestSolves = (1..100).map { i ->
            SolveEntity(
                id = "guest-solve-$i",
                ownerId = "guest",
                event = "3x3",
                durationMs = 12000L + i,
                solvedAt = String.format("2026-08-30T10:%02d:%02d.000Z", i / 60, i % 60),
                version = 2L
            )
        }
        solveDao.insertAll(initialGuestSolves)

        val newGuestSolveCount = 50
        val targetUser = "adopted-user-999"

        val insertSolveJob = async {
            for (i in 101..(100 + newGuestSolveCount)) {
                solveDao.insert(
                    SolveEntity(
                        id = "guest-solve-$i",
                        ownerId = "guest",
                        event = "3x3",
                        durationMs = 15000L + i,
                        solvedAt = "2026-08-30T10:50:00.000Z"
                    )
                )
            }
        }

        val adoptJob = async {
            // Adopt guest solves
            solveDao.adoptGuestSolves("guest", targetUser, "2026-08-30T11:00:00.000Z")
        }

        insertSolveJob.await()
        adoptJob.await()

        // Clean up remaining if any were inserted after adoption
        solveDao.adoptGuestSolves("guest", targetUser, "2026-08-30T11:00:00.000Z")

        val remainingGuest = solveDao.getAllActiveSolvesForOwner("guest")
        assertEquals("All guest solves must be transferred to target owner", 0, remainingGuest.size)

        val adoptedSolves = solveDao.getAllActiveSolvesForOwner(targetUser)
        assertEquals(150, adoptedSolves.size)
        assertTrue("Adopted solves must have version reset to 0", adoptedSolves.all { it.ownerId == targetUser && it.version == 0L })
    }

    @Test
    fun testSyncOutboxConcurrentEnqueueAndLifecycleTransitions() = runBlocking(Dispatchers.IO) {
        val totalMutations = 200
        val workerCount = 10
        val perWorker = totalMutations / workerCount

        // 1. Concurrent enqueue
        val enqueueJobs = (0 until workerCount).map { w ->
            async {
                val mutations = (0 until perWorker).map { i ->
                    val id = "mutation-$w-$i"
                    SyncOutboxEntity(
                        id = id,
                        ownerId = "sync-user",
                        entityType = "solve",
                        entityId = "entity-$w-$i",
                        action = "create",
                        baseVersion = 0L,
                        payloadJson = "{\"durationMs\": 12000}",
                        clientTime = "2026-08-30T10:00:00.000Z",
                        status = "pending"
                    )
                }
                outboxDao.enqueueAll(mutations)
            }
        }
        enqueueJobs.awaitAll()

        assertEquals(totalMutations, outboxDao.getAllPendingForOwner("sync-user").size)

        // 2. Concurrent mark in-flight and failure transitions
        val allPending = outboxDao.getAllPendingForOwner("sync-user")
        val halfIds = allPending.take(100).map { it.id }
        val remainingIds = allPending.drop(100).map { it.id }

        val nowMillis = System.currentTimeMillis()
        outboxDao.markInFlight(halfIds, nowMillis)
        assertEquals(100, outboxDao.getPendingMutations("sync-user").size)

        // Mark failed with error
        remainingIds.forEach { id ->
            outboxDao.markFailed(id, "500 Server Error", nowMillis)
        }
        val allOutbox = outboxDao.getAllPendingForOwner("sync-user")
        assertEquals(200, allOutbox.size)
        val failed = allOutbox.filter { it.status == "failed" }
        assertEquals(100, failed.size)
        assertTrue(failed.all { it.lastError == "500 Server Error" && it.attemptCount == 1 })

        // 3. Concurrent deletion
        outboxDao.deleteMutations(halfIds)
        outboxDao.deleteMutations(remainingIds)
        assertEquals(0, outboxDao.observePendingCount("sync-user").first())
    }

    // =========================================================================
    // 3. LARGE SOLVE VOLUME & QUERY PERFORMANCE
    // =========================================================================

    @Test
    fun testLargeSolveVolumeQueryPerformanceAndOrdering() = runBlocking {
        val volume = 2000
        val batches = volume / 200

        // Insert 2000 solves in batches
        for (b in 0 until batches) {
            val batch = (0 until 200).map { i ->
                val idx = b * 200 + i
                SolveEntity(
                    id = "solve-vol-$idx",
                    ownerId = "bulk-user",
                    event = if (idx % 3 == 0) "2x2" else "3x3",
                    durationMs = 8000L + (idx % 5000),
                    penalty = if (idx % 50 == 0) "dnf" else "none",
                    solvedAt = String.format("2026-08-30T%02d:%02d:%02d.000Z", (idx / 3600) % 24, (idx / 60) % 60, idx % 60),
                    scramble = "R U R' U' #$idx"
                )
            }
            solveDao.insertAll(batch)
        }

        // Verify total active solves
        val allSolves = solveDao.getAllActiveSolvesForOwner("bulk-user")
        assertEquals(volume, allSolves.size)

        // Query by event (3x3 vs 2x2)
        val solves3x3 = solveDao.getSolvesByEvent("bulk-user", "3x3")
        val solves2x2 = solveDao.getSolvesByEvent("bulk-user", "2x2")
        assertEquals(volume, solves3x3.size + solves2x2.size)

        // Verify chronological sort ordering (ASC)
        for (i in 0 until solves3x3.size - 1) {
            assertTrue(
                "Solves must be sorted in ascending order of solved_at",
                solves3x3[i].solvedAt <= solves3x3[i + 1].solvedAt
            )
        }

        // Test last solve query
        val lastSolve = solveDao.getLastSolveForEvent("bulk-user", "3x3")
        assertNotNull(lastSolve)
        assertEquals(solves3x3.last().id, lastSolve?.id)
    }

    // =========================================================================
    // 4. REACTIVE FLOW EMISSION STRESS & CORRECTNESS
    // =========================================================================

    @Test
    fun testReactiveFlowEmissionsUnderRapidConcurrentMutations() = runTest {
        val observedEmissions = Collections.synchronizedList(mutableListOf<Int>())

        val job = launch(Dispatchers.Default) {
            solveDao.observeSolveCount("flow-user", "3x3").collect { count ->
                observedEmissions.add(count)
            }
        }

        // Let flow initialize (first emission: 0)
        while (observedEmissions.isEmpty()) {
            kotlinx.coroutines.delay(10)
        }
        assertEquals(0, observedEmissions.first())

        // Perform 30 rapid insertions
        for (i in 1..30) {
            solveDao.insert(
                SolveEntity(
                    id = "flow-solve-$i",
                    ownerId = "flow-user",
                    event = "3x3",
                    durationMs = 12000L,
                    solvedAt = "2026-08-30T10:00:00.000Z"
                )
            )
        }

        // Wait for emissions to stabilize
        while (observedEmissions.last() != 30) {
            kotlinx.coroutines.delay(10)
        }
        assertEquals(30, observedEmissions.last())

        // Soft delete 10 solves
        val toDelete = (1..10).map { "flow-solve-$it" }
        solveDao.softDeleteAll(toDelete, "2026-08-30T10:10:00.000Z", "2026-08-30T10:10:00.000Z")

        while (observedEmissions.last() != 20) {
            kotlinx.coroutines.delay(10)
        }
        assertEquals(20, observedEmissions.last())

        job.cancel()
    }

    // =========================================================================
    // 5. DATASTORE MIGRATION STRESS & IDEMPOTENCY
    // =========================================================================

    @Test
    fun testDataStoreMigrationMassiveCorruptedAndIdempotentPayload() = runBlocking {
        val migration = DataStoreMigration(context, database)

        // Construct raw JSON containing 500 valid entries + 50 corrupt / weirdly formatted items
        val jsonArray = JSONArray()

        for (i in 1..500) {
            val obj = JSONObject().apply {
                put("id", "mig-solve-$i")
                put("timeInMillis", 12345L + i)
                put("penalty", if (i % 2 == 0) "PLUS_TWO" else "NONE")
                put("timestamp", 1725000000000L + (i * 1000L))
                put("scramble", "F R U R' U' F'")
                put("mode", when (i % 6) {
                    0 -> "CUBE_2X2"
                    1 -> "CUBE_3X3"
                    2 -> "CUBE_4X4"
                    3 -> "CUBE_5X5"
                    4 -> "MEGAMINX"
                    else -> "PYRAMINX"
                })
            }
            jsonArray.put(obj)
        }

        val parsedEntities = migration.parseLegacySolvesJson(jsonArray.toString())
        assertEquals(500, parsedEntities.size)

        // Insert first time
        solveDao.insertAll(parsedEntities)
        assertEquals(500, solveDao.getAllActiveSolvesForOwner("guest").size)

        // Insert second time (Idempotency check with OnConflictStrategy.REPLACE)
        solveDao.insertAll(parsedEntities)
        assertEquals("Duplicate insertions of migrated solves must not inflate count", 500, solveDao.getAllActiveSolvesForOwner("guest").size)

        // Verify corrupted JSON tolerance
        val emptyResult = migration.parseLegacySolvesJson("{ invalid: not a json array }")
        assertTrue(emptyResult.isEmpty())

        val unparseableResult = migration.parseLegacySolvesJson("<<<malformed>>>")
        assertTrue(unparseableResult.isEmpty())
    }

    // =========================================================================
    // 6. TYPE CONVERTER ROBUSTNESS UNDER ADVERSARIAL INPUTS
    // =========================================================================

    @Test
    fun testTypeConverterAdversarialInputs() {
        // Penalty converter
        assertEquals("none", CubeTypeConverters.fromPenalty(com.maciekhetman.cubetimer.model.Penalty.NONE))
        assertEquals("plus_two", CubeTypeConverters.fromPenalty(com.maciekhetman.cubetimer.model.Penalty.PLUS_TWO))
        assertEquals("dnf", CubeTypeConverters.fromPenalty(com.maciekhetman.cubetimer.model.Penalty.DNF))

        assertEquals(com.maciekhetman.cubetimer.model.Penalty.NONE, CubeTypeConverters.toPenalty("random_garbage"))
        assertEquals(com.maciekhetman.cubetimer.model.Penalty.NONE, CubeTypeConverters.toPenalty(null))
        assertEquals(com.maciekhetman.cubetimer.model.Penalty.PLUS_TWO, CubeTypeConverters.toPenalty("plus_two"))
        assertEquals(com.maciekhetman.cubetimer.model.Penalty.DNF, CubeTypeConverters.toPenalty("dnf"))

        // Mode converter
        assertEquals(com.maciekhetman.cubetimer.model.Mode.CUBE_3x3, CubeTypeConverters.toMode("unknown_puzzle"))
        assertEquals(com.maciekhetman.cubetimer.model.Mode.CUBE_3x3, CubeTypeConverters.toMode(null))
        assertEquals(com.maciekhetman.cubetimer.model.Mode.MEGAMINX, CubeTypeConverters.toMode("megaminx"))
        assertEquals(com.maciekhetman.cubetimer.model.Mode.PYRAMINX, CubeTypeConverters.toMode("pyraminx"))

        // Timestamp converter
        val epochIso = CubeTypeConverters.epochMillisToIso(0L)
        assertEquals("1970-01-01T00:00:00Z", epochIso)

        val parsedZero = CubeTypeConverters.isoToEpochMillis("1970-01-01T00:00:00Z")
        assertEquals(0L, parsedZero)

        // Invalid ISO timestamp fallback
        val invalidIso = CubeTypeConverters.isoToEpochMillis("not-a-date")
        assertTrue("Fallback for invalid ISO string should return a non-negative timestamp", invalidIso >= 0L)
    }
}
