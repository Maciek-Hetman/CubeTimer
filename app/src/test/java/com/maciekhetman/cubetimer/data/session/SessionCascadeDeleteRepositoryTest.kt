package com.maciekhetman.cubetimer.data.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.data.SolvesRepository
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.remote.NetworkModule
import com.maciekhetman.cubetimer.data.remote.dto.SessionSyncPayload
import com.maciekhetman.cubetimer.data.remote.dto.SolveSyncPayload
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Penalty
import com.maciekhetman.cubetimer.model.SolveTime
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SessionCascadeDeleteRepositoryTest {

    private lateinit var context: Context
    private lateinit var database: CubeDatabase
    private lateinit var repository: SessionRepositoryImpl
    private lateinit var solvesRepository: SolvesRepository
    private var syncTriggerCount = 0

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = CubeDatabase.createInMemory(context)
        syncTriggerCount = 0
        repository = SessionRepositoryImpl(
            database = database,
            sessionDao = database.sessionDao(),
            syncOutboxDao = database.syncOutboxDao(),
            syncTrigger = { syncTriggerCount++ }
        )
        solvesRepository = SolvesRepository(
            context = context,
            solveDao = database.solveDao(),
            sessionDao = database.sessionDao(),
            syncOutboxDao = database.syncOutboxDao(),
            database = database,
            syncTrigger = { syncTriggerCount++ }
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testDeleteSessionWithSolves_guest_softDeletesSessionAndSolvesAtomically() = runTest {
        val session = repository.createManualSession("Afternoon Practice", Mode.CUBE_3x3, ownerId = "guest")

        val s1 = SolveTime(id = "s-1", timeInMillis = 10000L, mode = Mode.CUBE_3x3, sessionId = session.id)
        val s2 = SolveTime(id = "s-2", timeInMillis = 11000L, mode = Mode.CUBE_3x3, sessionId = session.id)
        val s3 = SolveTime(id = "s-3", timeInMillis = 12000L, mode = Mode.CUBE_3x3, sessionId = session.id)
        solvesRepository.saveSolve(s1, ownerId = "guest", sessionId = session.id)
        solvesRepository.saveSolve(s2, ownerId = "guest", sessionId = session.id)
        solvesRepository.saveSolve(s3, ownerId = "guest", sessionId = session.id)

        assertEquals(1, repository.getActiveSessions("guest", Mode.CUBE_3x3).size)
        assertEquals(3, database.solveDao().getSolvesBySession("guest", session.id).size)

        val snapshot = repository.deleteSessionWithSolves(session.id, ownerId = "guest")
        assertNotNull(snapshot)
        assertEquals(session.id, snapshot?.session?.id)
        assertEquals(3, snapshot?.solves?.size)

        val sessionInDb = database.sessionDao().getSessionById(session.id)
        assertNotNull(sessionInDb)
        assertNotNull("Session deletedAt must be populated", sessionInDb?.deletedAt)
        assertEquals(0, repository.getActiveSessions("guest", Mode.CUBE_3x3).size)

        assertEquals(0, database.solveDao().getSolvesBySession("guest", session.id).size)
        val rawSolves = database.solveDao().getAllSolvesForOwner("guest")
        assertEquals(3, rawSolves.size)
        assertTrue("All child solves must have deletedAt populated", rawSolves.all { it.deletedAt != null })

        assertEquals(0, database.syncOutboxDao().countPending("guest"))
    }

    @Test
    fun testRestoreSessionWithSolves_guest_restoresBothSessionAndSolves() = runTest {
        val session = repository.createManualSession("Undo Target", Mode.CUBE_3x3, ownerId = "guest")
        val solve = SolveTime(
            id = "solve-restore-1",
            timeInMillis = 9500L,
            penalty = Penalty.PLUS_TWO,
            timestamp = 1726000000000L,
            scramble = "R U R' U'",
            mode = Mode.CUBE_3x3,
            sessionId = session.id
        )
        solvesRepository.saveSolve(solve, ownerId = "guest", sessionId = session.id)

        val snapshot = repository.deleteSessionWithSolves(session.id, ownerId = "guest")
        assertNotNull(snapshot)

        assertEquals(0, repository.getActiveSessions("guest", Mode.CUBE_3x3).size)
        assertEquals(0, database.solveDao().getSolvesBySession("guest", session.id).size)

        repository.restoreSessionWithSolves(snapshot!!, ownerId = "guest")

        val restoredSession = repository.getSessionById(session.id)
        assertNotNull(restoredSession)
        assertNull("Restored session deletedAt must be null", restoredSession?.deletedAt)
        assertFalse(restoredSession!!.isDeleted)
        assertEquals(1, repository.getActiveSessions("guest", Mode.CUBE_3x3).size)

        val restoredSolves = database.solveDao().getSolvesBySession("guest", session.id)
        assertEquals(1, restoredSolves.size)
        val s = restoredSolves[0]
        assertEquals("solve-restore-1", s.id)
        assertNull("Restored solve deletedAt must be null", s.deletedAt)
        assertEquals(9500L, s.durationMs)
        assertEquals("plus_two", s.penalty)
        assertEquals("R U R' U'", s.scramble)
        assertEquals(session.id, s.sessionId)
    }

    @Test
    fun testCascadeDeleteAndRestoreForAuthenticatedUser_generatesCorrectOutboxMutations() = runTest {
        val userId = "user-auth-m1"
        val session = repository.createManualSession("Online Session", Mode.CUBE_3x3, ownerId = userId)

        val s1 = SolveTime(id = "s-auth-1", timeInMillis = 10000L, mode = Mode.CUBE_3x3, sessionId = session.id)
        val s2 = SolveTime(id = "s-auth-2", timeInMillis = 11000L, mode = Mode.CUBE_3x3, sessionId = session.id)
        solvesRepository.saveSolve(s1, ownerId = userId, sessionId = session.id)
        solvesRepository.saveSolve(s2, ownerId = userId, sessionId = session.id)

        database.syncOutboxDao().clearOutbox(userId)
        val syncTriggersBefore = syncTriggerCount

        // Cascade delete
        val snapshot = repository.deleteSessionWithSolves(session.id, ownerId = userId)
        assertNotNull(snapshot)

        val deleteMutations = database.syncOutboxDao().getPendingMutations(userId)
        assertEquals(3, deleteMutations.size)

        val sessionDelete = deleteMutations.first { it.entityType == "session" }
        assertEquals("delete", sessionDelete.action)
        assertEquals(session.id, sessionDelete.entityId)
        assertNull(sessionDelete.payloadJson)

        val solveDeletes = deleteMutations.filter { it.entityType == "solve" }
        assertEquals(2, solveDeletes.size)
        assertTrue(solveDeletes.any { it.entityId == "s-auth-1" && it.action == "delete" })
        assertTrue(solveDeletes.any { it.entityId == "s-auth-2" && it.action == "delete" })
        assertTrue(solveDeletes.all { it.payloadJson == null })

        assertTrue("Sync trigger must be invoked on delete", syncTriggerCount > syncTriggersBefore)

        // Restore
        database.syncOutboxDao().clearOutbox(userId)
        repository.restoreSessionWithSolves(snapshot!!, ownerId = userId)

        val restoreMutations = database.syncOutboxDao().getPendingMutations(userId)
        assertEquals(3, restoreMutations.size)

        val sessionRestore = restoreMutations.first { it.entityType == "session" }
        assertEquals("upsert", sessionRestore.action)
        assertEquals(session.id, sessionRestore.entityId)
        assertNotNull(sessionRestore.payloadJson)
        val sessionPayload = NetworkModule.json.decodeFromString<SessionSyncPayload>(sessionRestore.payloadJson!!)
        assertEquals("Online Session", sessionPayload.name)

        val solveRestores = restoreMutations.filter { it.entityType == "solve" }
        assertEquals(2, solveRestores.size)
        assertTrue(solveRestores.all { it.action == "upsert" && it.payloadJson != null })
        val p1 = NetworkModule.json.decodeFromString<SolveSyncPayload>(solveRestores.first { it.entityId == "s-auth-1" }.payloadJson!!)
        assertEquals(session.id, p1.sessionId)
        assertEquals(10000L, p1.durationMs)
    }

    @Test
    fun testDeleteSessionWithSolves_emptySessionReturnsEmptySolvesSnapshot() = runTest {
        val session = repository.createManualSession("Empty Sess", Mode.CUBE_3x3, ownerId = "guest")
        val snapshot = repository.deleteSessionWithSolves(session.id, ownerId = "guest")
        assertNotNull(snapshot)
        assertEquals(session.id, snapshot?.session?.id)
        assertTrue(snapshot?.solves?.isEmpty() == true)

        repository.restoreSessionWithSolves(snapshot!!, ownerId = "guest")
        val restored = repository.getSessionById(session.id)
        assertNotNull(restored)
        assertNull(restored?.deletedAt)
    }

    @Test
    fun testDeleteSessionWithSolves_nonExistentSessionReturnsNull() = runTest {
        val result = repository.deleteSessionWithSolves("does-not-exist", ownerId = "guest")
        assertNull(result)
    }
}
