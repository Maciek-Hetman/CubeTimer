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
}
