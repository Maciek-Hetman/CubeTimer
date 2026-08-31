package com.maciekhetman.cubetimer.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.maciekhetman.cubetimer.data.SolvesRepository
import com.maciekhetman.cubetimer.data.local.entity.SessionEntity
import com.maciekhetman.cubetimer.data.solvesDataStore
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Penalty
import com.maciekhetman.cubetimer.model.SolveTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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

@RunWith(RobolectricTestRunner::class)
class SolvesRepositoryStressTest {

    private lateinit var context: Context
    private lateinit var database: CubeDatabase
    private lateinit var repository: SolvesRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = CubeDatabase.createInMemory(context)
        repository = SolvesRepository(
            context = context,
            solveDao = database.solveDao(),
            sessionDao = database.sessionDao(),
            database = database
        )
    }

    @After
    fun tearDown() = runTest {
        context.solvesDataStore.edit { it.clear() }
        database.close()
    }

    @Test
    fun testRapidPenaltyTransitions() = runTest {
        val solve = SolveTime(
            id = "solve-penalty-cycle",
            timeInMillis = 10000L,
            penalty = Penalty.NONE,
            mode = Mode.CUBE_3x3
        )
        repository.saveSolve(solve)

        // Rapidly alternate penalties
        val penalties = listOf(
            Penalty.PLUS_TWO,
            Penalty.DNF,
            Penalty.NONE,
            Penalty.PLUS_TWO,
            Penalty.NONE,
            Penalty.DNF,
            Penalty.PLUS_TWO
        )

        for (p in penalties) {
            repository.updateSolvePenalty(solve, p)
            val current = repository.solvesFlow.first().first()
            assertEquals(p, current.penalty)
        }

        val finalSolve = repository.solvesFlow.first().first()
        assertEquals(Penalty.PLUS_TWO, finalSolve.penalty)
        assertEquals(12000L, finalSolve.displayTime)
    }

    @Test
    fun testConcurrentPenaltyUpdatesOnDistinctSolves() = runTest {
        val count = 30
        val solves = (1..count).map { idx ->
            SolveTime(
                id = "concurrent-solve-$idx",
                timeInMillis = 10000L + idx,
                penalty = Penalty.NONE,
                mode = Mode.CUBE_3x3
            )
        }
        repository.saveSolves(solves)

        // Launch concurrent penalty updates
        val jobs = solves.mapIndexed { idx, s ->
            async(Dispatchers.IO) {
                val targetPenalty = if (idx % 2 == 0) Penalty.PLUS_TWO else Penalty.DNF
                repository.updateSolvePenalty(s, targetPenalty)
            }
        }
        jobs.awaitAll()

        val updatedSolves = repository.solvesFlow.first()
        assertEquals(count, updatedSolves.size)
        for (i in 1..count) {
            val s = updatedSolves.find { it.id == "concurrent-solve-$i" }
            assertNotNull(s)
            val expectedPenalty = if ((i - 1) % 2 == 0) Penalty.PLUS_TWO else Penalty.DNF
            assertEquals(expectedPenalty, s?.penalty)
        }
    }

    @Test
    fun testSoftDeleteFlowVisibilityAndDatabaseInvariants() = runTest {
        val solves = (1..10).map { idx ->
            SolveTime(
                id = "soft-del-$idx",
                timeInMillis = 10000L + idx,
                mode = Mode.CUBE_3x3
            )
        }
        repository.saveSolves(solves)
        assertEquals(10, repository.solvesFlow.first().size)

        // Soft delete 4 solves
        val toDelete = solves.take(4)
        for (s in toDelete) {
            repository.deleteSolve(s)
        }

        // Active flow should only see 6 solves
        val activeSolves = repository.solvesFlow.first()
        assertEquals(6, activeSolves.size)
        for (s in toDelete) {
            assertTrue(activeSolves.none { it.id == s.id })
        }

        // Raw database query should still contain all 10 records
        val allDbSolves = database.solveDao().getAllSolvesForOwner("guest")
        assertEquals(10, allDbSolves.size)

        val deletedDbSolves = allDbSolves.filter { it.deletedAt != null }
        assertEquals(4, deletedDbSolves.size)

        val activeDbSolves = allDbSolves.filter { it.deletedAt == null }
        assertEquals(6, activeDbSolves.size)
    }

    @Test
    fun testDeleteNonExistentSolveIsSafe() = runTest {
        val ghostSolve = SolveTime(id = "ghost-solve-id", timeInMillis = 5000L)
        // Should execute cleanly without error
        repository.deleteSolve(ghostSolve)
        assertEquals(0, repository.solvesFlow.first().size)
    }

    @Test
    fun testHighVolumeBulkSaveAndDeltaSync() = runTest {
        val count = 200
        val initialSolves = (1..count).map { idx ->
            SolveTime(
                id = "bulk-$idx",
                timeInMillis = 10000L + idx,
                mode = Mode.CUBE_3x3
            )
        }

        repository.saveSolves(initialSolves)
        assertEquals(count, repository.solvesFlow.first().size)

        // Delta: keep 1..100, remove 101..200, add 201..250
        val nextSolves = (1..100).map { idx ->
            SolveTime(
                id = "bulk-$idx",
                timeInMillis = 20000L + idx, // updated duration
                mode = Mode.CUBE_3x3
            )
        } + (201..250).map { idx ->
            SolveTime(
                id = "bulk-$idx",
                timeInMillis = 10000L + idx,
                mode = Mode.CUBE_3x3
            )
        }

        repository.saveSolves(nextSolves)

        val activeSolves = repository.solvesFlow.first()
        assertEquals(150, activeSolves.size)

        // Check modified solve duration updated
        val solve1 = activeSolves.find { it.id == "bulk-1" }
        assertEquals(20001L, solve1?.timeInMillis)

        // Check new solve exists
        val solve201 = activeSolves.find { it.id == "bulk-201" }
        assertNotNull(solve201)

        // Check total raw rows in DB (200 initial + 50 new = 250 total rows)
        val allDbSolves = database.solveDao().getAllSolvesForOwner("guest")
        assertEquals(250, allDbSolves.size)

        val softDeleted = allDbSolves.filter { it.deletedAt != null }
        assertEquals(100, softDeleted.size) // 101..200 soft deleted
    }

    @Test
    fun testUndoSnackbarRestorationLifecycle() = runTest {
        val solves = (1..5).map { idx ->
            SolveTime(
                id = "undo-$idx",
                timeInMillis = 10000L + idx,
                mode = Mode.CUBE_3x3
            )
        }
        repository.saveSolves(solves)
        assertEquals(5, repository.solvesFlow.first().size)

        // User clears solves (e.g. bulk clear)
        repository.clearAllSolves("guest")
        assertEquals(0, repository.solvesFlow.first().size)

        // Verify they are marked soft-deleted
        val dbDeleted = database.solveDao().getAllSolvesForOwner("guest")
        assertEquals(5, dbDeleted.size)
        assertTrue(dbDeleted.all { it.deletedAt != null })

        // User clicks Undo in Snackbar -> restoreSolves
        repository.restoreSolves(solves, "guest")

        // Solves should immediately be active again
        val restored = repository.solvesFlow.first()
        assertEquals(5, restored.size)

        val dbRestored = database.solveDao().getAllSolvesForOwner("guest")
        assertEquals(5, dbRestored.size)
        assertTrue(dbRestored.all { it.deletedAt == null })
    }

    @Test
    fun testModeAndSessionIsolation() = runTest {
        // Pre-create sessions in DB
        database.sessionDao().insert(
            SessionEntity(id = "sess-a", ownerId = "guest", event = "3x3", name = "Session A", startedAt = "2026-08-30T10:00:00Z")
        )
        database.sessionDao().insert(
            SessionEntity(id = "sess-b", ownerId = "guest", event = "3x3", name = "Session B", startedAt = "2026-08-30T10:00:00Z")
        )

        val s3x3_1 = SolveTime(id = "s-3x3-1", timeInMillis = 10000L, mode = Mode.CUBE_3x3)
        val s3x3_2 = SolveTime(id = "s-3x3-2", timeInMillis = 12000L, mode = Mode.CUBE_3x3)
        val s2x2_1 = SolveTime(id = "s-2x2-1", timeInMillis = 3000L, mode = Mode.CUBE_2x2)
        val sPyra_1 = SolveTime(id = "s-pyra-1", timeInMillis = 4000L, mode = Mode.PYRAMINX)

        repository.saveSolve(s3x3_1, ownerId = "guest", sessionId = "sess-a")
        repository.saveSolve(s3x3_2, ownerId = "guest", sessionId = "sess-b")
        repository.saveSolve(s2x2_1, ownerId = "guest", sessionId = "sess-a")
        repository.saveSolve(sPyra_1, ownerId = "guest", sessionId = "sess-a")

        // Mode filtered queries
        assertEquals(2, repository.getSolvesFlow(Mode.CUBE_3x3).first().size)
        assertEquals(1, repository.getSolvesFlow(Mode.CUBE_2x2).first().size)
        assertEquals(1, repository.getSolvesFlow(Mode.PYRAMINX).first().size)
        assertEquals(0, repository.getSolvesFlow(Mode.CUBE_4x4).first().size)

        // Session filtered queries
        assertEquals(3, repository.getSolvesBySessionFlow("sess-a").first().size)
        assertEquals(1, repository.getSolvesBySessionFlow("sess-b").first().size)
    }

    @Test
    fun testForeignKeyConstraintEnforcementOnInvalidSession() = runTest {
        val solveWithInvalidSession = SolveTime(
            id = "solve-invalid-session",
            timeInMillis = 12000L,
            mode = Mode.CUBE_3x3
        )

        try {
            repository.saveSolve(solveWithInvalidSession, ownerId = "guest", sessionId = "non-existent-session-id")
            fail("Expected SQLiteConstraintException when referencing non-existent session_id")
        } catch (e: Exception) {
            assertTrue("Expected constraint exception, got ${e::class.simpleName}", e is android.database.sqlite.SQLiteConstraintException || e.cause is android.database.sqlite.SQLiteConstraintException)
        }
    }

    @Test
    fun testConcurrentAppTimeUpdates() = runTest {
        val modes = listOf(Mode.CUBE_2x2, Mode.CUBE_3x3, Mode.CUBE_4x4, Mode.CUBE_5x5, Mode.MEGAMINX, Mode.PYRAMINX)

        val jobs = modes.mapIndexed { idx, mode ->
            async(Dispatchers.IO) {
                repository.saveAppTime(mode, (idx + 1) * 10000L)
            }
        }
        jobs.awaitAll()

        for ((idx, mode) in modes.withIndex()) {
            val time = repository.getAppTimeFlow(mode).first()
            assertEquals((idx + 1) * 10000L, time)
        }
    }
}
