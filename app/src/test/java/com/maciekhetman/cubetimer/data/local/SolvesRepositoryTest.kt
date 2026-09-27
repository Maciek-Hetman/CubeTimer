package com.maciekhetman.cubetimer.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.maciekhetman.cubetimer.data.SolvesRepository
import com.maciekhetman.cubetimer.data.solvesDataStore
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Penalty
import com.maciekhetman.cubetimer.model.SolveTime
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

@RunWith(RobolectricTestRunner::class)
class SolvesRepositoryTest {

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
    fun testSaveSolveAndObserveFlow() = runTest {
        val solve1 = SolveTime(
            id = "repo-solve-1",
            timeInMillis = 11200L,
            penalty = Penalty.NONE,
            timestamp = 1725000000000L,
            scramble = "R U R' U'",
            mode = Mode.CUBE_3x3
        )

        repository.solvesFlow.test {
            assertEquals(0, awaitItem().size)

            repository.saveSolve(solve1)
            val updated = awaitItem()
            assertEquals(1, updated.size)
            assertEquals("repo-solve-1", updated[0].id)
            assertEquals(11200L, updated[0].timeInMillis)
            assertEquals(Penalty.NONE, updated[0].penalty)
            assertEquals(Mode.CUBE_3x3, updated[0].mode)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun testSaveSolvesBulkAndSoftDelete() = runTest {
        val solve1 = SolveTime(
            id = "solve-bulk-1",
            timeInMillis = 10000L,
            mode = Mode.CUBE_3x3
        )
        val solve2 = SolveTime(
            id = "solve-bulk-2",
            timeInMillis = 12000L,
            mode = Mode.CUBE_3x3
        )

        repository.saveSolves(listOf(solve1, solve2))
        var solves = repository.solvesFlow.first()
        assertEquals(2, solves.size)

        // Delete solve1 by saving list with only solve2
        repository.saveSolves(listOf(solve2))
        solves = repository.solvesFlow.first()
        assertEquals(1, solves.size)
        assertEquals("solve-bulk-2", solves[0].id)

        // Clear all by saving empty list
        repository.saveSolves(emptyList())
        solves = repository.solvesFlow.first()
        assertEquals(0, solves.size)
    }

    @Test
    fun testUpdateSolvePenalty() = runTest {
        val solve = SolveTime(
            id = "solve-penalty-test",
            timeInMillis = 15000L,
            penalty = Penalty.NONE,
            mode = Mode.CUBE_3x3
        )
        repository.saveSolve(solve)

        repository.updateSolvePenalty(solve, Penalty.PLUS_TWO)
        val solves = repository.solvesFlow.first()
        assertEquals(1, solves.size)
        assertEquals(Penalty.PLUS_TWO, solves[0].penalty)
        assertEquals(17000L, solves[0].displayTime)
    }

    @Test
    fun testRestoreSolves() = runTest {
        val solve1 = SolveTime(id = "s-res-1", timeInMillis = 10000L, mode = Mode.CUBE_3x3)
        val solve2 = SolveTime(id = "s-res-2", timeInMillis = 11000L, mode = Mode.CUBE_3x3)

        repository.saveSolves(listOf(solve1, solve2))
        repository.clearAllSolves()
        assertEquals(0, repository.solvesFlow.first().size)

        repository.restoreSolves(listOf(solve1, solve2))
        assertEquals(2, repository.solvesFlow.first().size)
    }

    @Test
    fun testAppTimePersistence() = runTest {
        repository.saveAppTime(Mode.CUBE_3x3, 45000L)
        val appTime = repository.getAppTimeFlow(Mode.CUBE_3x3).first()
        assertEquals(45000L, appTime)
    }

    @Test
    fun testDeleteSolvesByIdsAndUndoRestore() = runTest {
        val s1 = SolveTime(id = "s-id-1", timeInMillis = 10000L, mode = Mode.CUBE_3x3)
        val s2 = SolveTime(id = "s-id-2", timeInMillis = 12000L, mode = Mode.CUBE_3x3)
        val s3 = SolveTime(id = "s-id-3", timeInMillis = 14000L, mode = Mode.CUBE_3x3)
        repository.saveSolves(listOf(s1, s2, s3))

        val deletedList = repository.deleteSolvesByIds(listOf("s-id-1", "s-id-2"), "guest")
        assertEquals(2, deletedList.size)
        assertTrue(deletedList.any { it.id == "s-id-1" })
        assertTrue(deletedList.any { it.id == "s-id-2" })

        val remaining = repository.solvesFlow.first()
        assertEquals(1, remaining.size)
        assertEquals("s-id-3", remaining[0].id)

        // Undo restoration
        repository.restoreSolves(deletedList, "guest")
        val restored = repository.solvesFlow.first()
        assertEquals(3, restored.size)
    }

    @Test
    fun testDeleteSolvesByIds_handlesNonExistentAndAlreadyDeletedIdsGracefully() = runTest {
        val s1 = SolveTime(id = "s-exist", timeInMillis = 10000L, mode = Mode.CUBE_3x3)
        repository.saveSolve(s1)

        val deleted = repository.deleteSolvesByIds(listOf("s-exist", "non-existent-id"), ownerId = "guest")
        assertEquals(1, deleted.size)
        assertEquals("s-exist", deleted[0].id)

        val deletedAgain = repository.deleteSolvesByIds(listOf("s-exist"), ownerId = "guest")
        assertTrue(deletedAgain.isEmpty())

        val emptyResult = repository.deleteSolvesByIds(emptyList(), ownerId = "guest")
        assertTrue(emptyResult.isEmpty())
    }

    @Test
    fun testClearAllSolvesInScope() = runTest {
        val s1 = SolveTime(id = "s-3x3-1", timeInMillis = 10000L, mode = Mode.CUBE_3x3)
        val s2 = SolveTime(id = "s-3x3-2", timeInMillis = 11000L, mode = Mode.CUBE_3x3)
        val s3 = SolveTime(id = "s-2x2-1", timeInMillis = 4000L, mode = Mode.CUBE_2x2)
        repository.saveSolves(listOf(s1, s2, s3))

        // Clear only 3x3 scope
        val cleared3x3 = repository.clearAllSolvesInScope(Mode.CUBE_3x3, "guest")
        assertEquals(2, cleared3x3.size)

        // Verify 2x2 remains intact
        val remaining = repository.getAllActiveSolves("guest")
        assertEquals(1, remaining.size)
        assertEquals("s-2x2-1", remaining[0].id)

        // Clear all remaining (mode = null)
        val clearedAll = repository.clearAllSolvesInScope(null, "guest")
        assertEquals(1, clearedAll.size)
        assertEquals(0, repository.getAllActiveSolves("guest").size)
    }

    @Test
    fun testAuthenticatedBatchDeleteAndScopeClear_enqueuesDeleteMutations() = runTest {
        val userId = "user-batch-del"
        val s1 = SolveTime(id = "s-auth-1", timeInMillis = 9000L, mode = Mode.CUBE_3x3)
        val s2 = SolveTime(id = "s-auth-2", timeInMillis = 9500L, mode = Mode.CUBE_3x3)
        repository.saveSolve(s1, ownerId = userId)
        repository.saveSolve(s2, ownerId = userId)
        database.syncOutboxDao().clearOutbox(userId)

        val deleted = repository.deleteSolvesByIds(listOf("s-auth-1"), ownerId = userId)
        assertEquals(1, deleted.size)

        val pending = database.syncOutboxDao().getPendingMutations(userId)
        assertEquals(1, pending.size)
        assertEquals("delete", pending[0].action)
        assertEquals("solve", pending[0].entityType)
        assertEquals("s-auth-1", pending[0].entityId)
        assertNull(pending[0].payloadJson)

        database.syncOutboxDao().clearOutbox(userId)
        val cleared = repository.clearAllSolvesInScope(Mode.CUBE_3x3, ownerId = userId)
        assertEquals(1, cleared.size)
        assertEquals("s-auth-2", cleared[0].id)

        val pendingClear = database.syncOutboxDao().getPendingMutations(userId)
        assertEquals(1, pendingClear.size)
        assertEquals("delete", pendingClear[0].action)
        assertEquals("s-auth-2", pendingClear[0].entityId)

        database.syncOutboxDao().clearOutbox(userId)
        repository.restoreSolves(listOf(s1, s2), ownerId = userId)
        val pendingRestore = database.syncOutboxDao().getPendingMutations(userId)
        assertEquals(2, pendingRestore.size)
        assertTrue(pendingRestore.all { it.action == "upsert" && it.entityType == "solve" })
    }
}
