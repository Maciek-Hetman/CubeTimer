package com.maciekhetman.cubetimer.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.data.SolvesRepository
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import com.maciekhetman.cubetimer.data.local.mapper.toSolveTime
import com.maciekhetman.cubetimer.data.remote.NetworkModule
import com.maciekhetman.cubetimer.data.remote.dto.SolveSyncPayload
import com.maciekhetman.cubetimer.data.solvesDataStore
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Penalty
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Another client can sync an event this app has no [Mode] for (e.g. "skewb"). The domain model
 * can only call such a solve 3x3, so every path that writes a row back from a [SolveTime] must
 * leave the stored event alone - in the row and in the upload queued for the server.
 */
@RunWith(RobolectricTestRunner::class)
class SolvesRepositoryUnknownEventTest {

    private val owner = "user-xyz"
    private val solveId = "skewb-solve-1"

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
            syncOutboxDao = database.syncOutboxDao(),
            database = database
        )
    }

    @After
    fun tearDown() = runTest {
        context.solvesDataStore.edit { it.clear() }
        database.close()
    }

    private suspend fun seedSkewbSolve(): SolveEntity {
        val entity = SolveEntity(
            id = solveId,
            ownerId = owner,
            event = "skewb",
            durationMs = 6_500L,
            solvedAt = "2026-08-30T09:00:00.000Z",
            version = 3L
        )
        database.solveDao().insert(entity)
        return entity
    }

    private suspend fun storedEvent(): String? = database.solveDao().getSolveById(solveId)?.event

    /** The `event` of the newest upsert queued for the solve, as it would be sent to the server. */
    private suspend fun queuedUpsertEvent(): String {
        val upsert = database.syncOutboxDao().getAllPendingForOwner(owner)
            .last { it.entityId == solveId && it.action == "upsert" }
        return NetworkModule.json.decodeFromString<SolveSyncPayload>(upsert.payloadJson!!).event
    }

    @Test
    fun updatePenaltyThenDeleteThenUndo_keepTheStoredEvent() = runTest {
        val solve = seedSkewbSolve().toSolveTime()
        assertEquals("the domain model can only call it 3x3", Mode.CUBE_3x3, solve.mode)

        repository.updateSolvePenalty(solve, Penalty.PLUS_TWO, ownerId = owner)
        assertEquals("skewb", storedEvent())
        assertEquals("skewb", queuedUpsertEvent())

        val deleted = repository.deleteSolvesByIds(listOf(solveId), ownerId = owner)
        assertEquals(1, deleted.size)
        assertEquals("skewb", storedEvent())

        database.syncOutboxDao().clearOutbox(owner)
        repository.restoreSolves(deleted, ownerId = owner)
        assertEquals("skewb", storedEvent())
        assertEquals("skewb", queuedUpsertEvent())
        assertNull(database.solveDao().getSolveById(solveId)?.deletedAt)
    }

    @Test
    fun saveSolve_overAnExistingRow_keepsTheStoredEvent() = runTest {
        val solve = seedSkewbSolve().toSolveTime()

        repository.saveSolve(solve.copy(timeInMillis = 7_250L), ownerId = owner)

        assertEquals(7_250L, database.solveDao().getSolveById(solveId)?.durationMs)
        assertEquals("skewb", storedEvent())
        assertEquals("skewb", queuedUpsertEvent())
    }

    @Test
    fun restoreSolves_withAnExplicitModeChange_writesThatMode() = runTest {
        val solve = seedSkewbSolve().toSolveTime()

        repository.restoreSolves(listOf(solve.copy(mode = Mode.CUBE_4x4)), ownerId = owner)

        assertEquals("4x4", storedEvent())
        assertEquals("4x4", queuedUpsertEvent())
    }
}
