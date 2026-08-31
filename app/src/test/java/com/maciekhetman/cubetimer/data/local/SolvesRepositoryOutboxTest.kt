package com.maciekhetman.cubetimer.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.data.SolvesRepository
import com.maciekhetman.cubetimer.data.remote.NetworkModule
import com.maciekhetman.cubetimer.data.remote.dto.SolveSyncPayload
import com.maciekhetman.cubetimer.data.solvesDataStore
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Penalty
import com.maciekhetman.cubetimer.model.SolveTime
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
class SolvesRepositoryOutboxTest {

    private lateinit var context: Context
    private lateinit var database: CubeDatabase
    private lateinit var repository: SolvesRepository
    private var syncTriggerCount = 0

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = CubeDatabase.createInMemory(context)
        syncTriggerCount = 0
        repository = SolvesRepository(
            context = context,
            solveDao = database.solveDao(),
            sessionDao = database.sessionDao(),
            syncOutboxDao = database.syncOutboxDao(),
            database = database,
            syncTrigger = { syncTriggerCount++ }
        )
    }

    @After
    fun tearDown() = runTest {
        context.solvesDataStore.edit { it.clear() }
        database.close()
    }

    @Test
    fun testGuestSolvesDoNotEnqueueOutboxMutations() = runTest {
        database.sessionDao().insert(
            com.maciekhetman.cubetimer.data.local.entity.SessionEntity(
                id = "session-1",
                ownerId = "guest",
                name = "Session 1",
                event = "3x3",
                kind = "manual",
                startedAt = java.time.Instant.now().toString()
            )
        )

        val solve = SolveTime(
            id = "guest-solve-1",
            timeInMillis = 9500L,
            penalty = Penalty.NONE,
            mode = Mode.CUBE_3x3,
            sessionId = "session-1"
        )

        repository.saveSolve(solve, ownerId = "guest")
        assertEquals(0, database.syncOutboxDao().countPending("guest"))

        repository.updateSolvePenalty(solve, Penalty.PLUS_TWO, ownerId = "guest")
        assertEquals(0, database.syncOutboxDao().countPending("guest"))

        repository.deleteSolve(solve, ownerId = "guest")
        assertEquals(0, database.syncOutboxDao().countPending("guest"))
    }

    @Test
    fun testAuthenticatedUserSaveSolveEnqueuesUpsertMutation() = runTest {
        database.sessionDao().insert(
            com.maciekhetman.cubetimer.data.local.entity.SessionEntity(
                id = "user-sess-1",
                ownerId = "user-xyz",
                name = "User Session",
                event = "3x3",
                kind = "manual",
                startedAt = java.time.Instant.now().toString()
            )
        )

        val solve = SolveTime(
            id = "user-solve-1",
            timeInMillis = 8750L,
            penalty = Penalty.NONE,
            mode = Mode.CUBE_3x3,
            sessionId = "user-sess-1"
        )

        repository.saveSolve(solve, ownerId = "user-xyz", sessionId = "user-sess-1")

        val pending = database.syncOutboxDao().getPendingMutations("user-xyz")
        assertEquals(1, pending.size)

        val mutation = pending[0]
        assertEquals("user-xyz", mutation.ownerId)
        assertEquals("solve", mutation.entityType)
        assertEquals("user-solve-1", mutation.entityId)
        assertEquals("upsert", mutation.action)
        assertEquals(0L, mutation.baseVersion)
        assertNotNull(mutation.payloadJson)

        val payload = NetworkModule.json.decodeFromString<SolveSyncPayload>(mutation.payloadJson!!)
        assertEquals("user-solve-1", payload.id)
        assertEquals("user-sess-1", payload.sessionId)
        assertEquals(8750L, payload.durationMs)
        assertEquals("none", payload.penalty)
        assertEquals("3x3", payload.event)

        assertEquals(1, syncTriggerCount)
    }

    @Test
    fun testAuthenticatedUserUpdatePenaltyEnqueuesUpsertMutation() = runTest {
        val solve = SolveTime(
            id = "user-solve-penalty",
            timeInMillis = 10000L,
            penalty = Penalty.NONE,
            mode = Mode.CUBE_3x3
        )

        repository.saveSolve(solve, ownerId = "user-xyz")
        assertEquals(1, database.syncOutboxDao().countPending("user-xyz"))

        repository.updateSolvePenalty(solve, Penalty.PLUS_TWO, ownerId = "user-xyz")
        val pending = database.syncOutboxDao().getPendingMutations("user-xyz")
        assertEquals(2, pending.size)

        val mutation = pending.last()
        assertEquals("upsert", mutation.action)
        val payload = NetworkModule.json.decodeFromString<SolveSyncPayload>(mutation.payloadJson!!)
        assertEquals("plus_two", payload.penalty)
    }

    @Test
    fun testAuthenticatedUserDeleteSolveEnqueuesDeleteMutation() = runTest {
        val solve = SolveTime(
            id = "user-solve-del",
            timeInMillis = 12000L,
            penalty = Penalty.NONE,
            mode = Mode.CUBE_3x3
        )

        repository.saveSolve(solve, ownerId = "user-xyz")
        assertEquals(1, database.syncOutboxDao().countPending("user-xyz"))

        repository.deleteSolve(solve, ownerId = "user-xyz")
        val pending = database.syncOutboxDao().getPendingMutations("user-xyz")
        assertEquals(2, pending.size)

        val mutation = pending.last()
        assertEquals("delete", mutation.action)
        assertEquals("solve", mutation.entityType)
        assertEquals("user-solve-del", mutation.entityId)
        assertNull(mutation.payloadJson)
    }

    @Test
    fun testAuthenticatedClearAllSolvesEnqueuesDeleteMutations() = runTest {
        val solve1 = SolveTime(id = "s-1", timeInMillis = 10000L, mode = Mode.CUBE_3x3)
        val solve2 = SolveTime(id = "s-2", timeInMillis = 11000L, mode = Mode.CUBE_3x3)

        repository.saveSolve(solve1, ownerId = "user-xyz")
        repository.saveSolve(solve2, ownerId = "user-xyz")
        assertEquals(2, database.syncOutboxDao().countPending("user-xyz"))

        repository.clearAllSolves(ownerId = "user-xyz")
        val pending = database.syncOutboxDao().getPendingMutations("user-xyz")
        assertEquals(4, pending.size) // 2 inserts + 2 deletes

        val deleteMutations = pending.filter { it.action == "delete" }
        assertEquals(2, deleteMutations.size)
        assertTrue(deleteMutations.any { it.entityId == "s-1" })
        assertTrue(deleteMutations.any { it.entityId == "s-2" })
    }
}
