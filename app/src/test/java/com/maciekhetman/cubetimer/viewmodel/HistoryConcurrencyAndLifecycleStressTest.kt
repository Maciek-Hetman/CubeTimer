package com.maciekhetman.cubetimer.viewmodel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.data.SolvesRepository
import com.maciekhetman.cubetimer.data.auth.AuthManager
import com.maciekhetman.cubetimer.data.auth.AuthResult
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.local.converter.CubeTypeConverters
import com.maciekhetman.cubetimer.data.local.entity.SessionEntity
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import com.maciekhetman.cubetimer.data.local.mapper.toSolveTime
import com.maciekhetman.cubetimer.data.session.DeletedSessionSnapshot
import com.maciekhetman.cubetimer.data.session.SessionManagerImpl
import com.maciekhetman.cubetimer.data.session.SessionRepositoryImpl
import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Penalty
import com.maciekhetman.cubetimer.model.Session
import com.maciekhetman.cubetimer.model.SolveTime
import com.maciekhetman.cubetimer.model.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Adversarial Concurrency and Coroutine Lifecycle Stress Harness for Reworked History Module.
 *
 * Scenarios:
 * 1. Rapid session expand/collapse cycles under continuous background database mutations.
 * 2. Multi-select operations during simultaneous background sync / remote solve deletions.
 * 3. Ghost selection vulnerability check on single solve deletion.
 * 4. Cache desynchronization vulnerability check on single and batch solve undo.
 * 5. Session cascade deletion and restoration concurrency under active StateFlow collection.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HistoryConcurrencyAndLifecycleStressTest {

    private lateinit var testDispatcher: TestDispatcher
    private lateinit var application: Application
    private lateinit var database: CubeDatabase
    private lateinit var solvesRepository: SolvesRepository
    private lateinit var sessionRepository: SessionRepositoryImpl
    private lateinit var sessionManager: SessionManagerImpl
    private lateinit var fakeAuthManager: FakeAuthManager
    private lateinit var viewModel: HistoryViewModel

    @Before
    fun setup() {
        testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        application = ApplicationProvider.getApplicationContext()

        val directExecutor = java.util.concurrent.Executor { it.run() }
        database = CubeDatabase.createInMemory(
            context = application,
            queryExecutor = directExecutor,
            transactionExecutor = directExecutor
        )

        solvesRepository = SolvesRepository(
            context = application,
            solveDao = database.solveDao(),
            sessionDao = database.sessionDao(),
            syncOutboxDao = database.syncOutboxDao(),
            database = database,
            ioDispatcher = testDispatcher
        )
        sessionRepository = SessionRepositoryImpl(
            database = database,
            sessionDao = database.sessionDao(),
            syncOutboxDao = database.syncOutboxDao(),
            solveDao = database.solveDao(),
            ioDispatcher = testDispatcher
        )
        fakeAuthManager = FakeAuthManager()
        sessionManager = SessionManagerImpl(
            context = application,
            sessionRepository = sessionRepository,
            solveDao = database.solveDao(),
            authManager = fakeAuthManager
        )
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    private fun createViewModel(): HistoryViewModel {
        return HistoryViewModel(
            application = application,
            solvesRepository = solvesRepository,
            sessionManager = sessionManager,
            sessionRepository = sessionRepository,
            authManager = fakeAuthManager,
            database = database,
            sessionDao = database.sessionDao(),
            solveDao = database.solveDao(),
            syncOutboxDao = database.syncOutboxDao(),
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )
    }

    private suspend fun createSessionWithSolves(
        name: String,
        mode: Mode = Mode.CUBE_3x3,
        ownerId: String = "guest",
        durationsMs: List<Long> = listOf(10000L, 12000L, 15000L)
    ): Pair<Session, List<SolveEntity>> {
        val session = sessionRepository.createManualSession(name, mode, ownerId)
        val entities = durationsMs.mapIndexed { index, duration ->
            SolveEntity(
                id = "${session.id}-solve-$index",
                ownerId = ownerId,
                sessionId = session.id,
                event = CubeTypeConverters.fromMode(mode),
                durationMs = duration,
                penalty = "none",
                solvedAt = Instant.parse("2026-09-12T10:00:00Z").plus(index.toLong(), ChronoUnit.MINUTES).toString(),
                scramble = "R U R' U'",
                version = 0L
            )
        }
        database.solveDao().insertAll(entities)
        return Pair(session, entities)
    }

    @Suppress("UNCHECKED_CAST")
    private fun getInternalExpandedJobs(vm: HistoryViewModel): Map<String, Job> {
        val field = HistoryViewModel::class.java.getDeclaredField("expandedSessionJobs")
        field.isAccessible = true
        return field.get(vm) as Map<String, Job>
    }

    // =============================================================================================
    // Challenge 1: Rapid Session Expand/Collapse Cycles Under Continuous Database Mutations
    // =============================================================================================
    @Test
    fun testRapidSessionExpandCollapseUnderActiveDatabaseMutations() = runTest(testDispatcher) {
        val sessionCount = 5
        val sessions = (0 until sessionCount).map { i ->
            createSessionWithSolves("Session-$i", durationsMs = listOf(10000L, 11000L, 12000L)).first
        }

        viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(5, viewModel.uiState.value.sessionGroups.size)
        assertTrue(viewModel.uiState.value.expandedSessionIds.isEmpty())

        // Launch concurrent background worker continuously inserting and updating solves in Room
        val mutationJob = launch {
            for (round in 0 until 20) {
                val sId = sessions[round % sessionCount].id
                val newSolve = SolveEntity(
                    id = "dynamic-$round",
                    ownerId = "guest",
                    sessionId = sId,
                    event = "3x3",
                    durationMs = 9000L + round * 100,
                    penalty = if (round % 3 == 0) "plus_two" else "none",
                    solvedAt = Instant.parse("2026-09-12T11:00:00Z").plus(round.toLong(), ChronoUnit.MINUTES).toString(),
                    scramble = "R U R' U'",
                    version = 0L
                )
                database.solveDao().insert(newSolve)
            }
        }

        // Simultaneously hammer 100 rapid expand / collapse toggles across sessions
        val togglingJob = launch {
            for (round in 0 until 100) {
                val targetSession = sessions[round % sessionCount]
                viewModel.toggleSessionExpanded(targetSession.id)
            }
        }

        listOf(mutationJob, togglingJob).forEach { it.join() }
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(5, state.sessionGroups.size)

        // Verify coroutine lifecycle cleanup:
        // Expanded jobs map in ViewModel MUST strictly match uiState.expandedSessionIds
        val internalJobs = getInternalExpandedJobs(viewModel)
        assertEquals(state.expandedSessionIds, internalJobs.keys)

        // All active jobs must be active; all collapsed sessions must have no active jobs
        for (session in sessions) {
            val isExpanded = state.expandedSessionIds.contains(session.id)
            val group = state.sessionGroups.first { it.id == session.id }
            assertEquals(isExpanded, group.isExpanded)
            if (isExpanded) {
                assertTrue(internalJobs.containsKey(session.id))
                assertTrue(internalJobs[session.id]?.isActive == true)
                assertFalse(group.isSolvesLoading)
                assertTrue("Expanded group should contain solves", group.solves.isNotEmpty())
            } else {
                assertFalse(internalJobs.containsKey(session.id))
                assertTrue("Collapsed group child solves list should be empty", group.solves.isEmpty())
            }
        }
    }

    // =============================================================================================
    // Challenge 2: Simultaneous Multi-Select Operations During Background Sync / Remote Deletion
    // =============================================================================================
    @Test
    fun testMultiSelectDuringSimultaneousBackgroundSyncDeletions() = runTest(testDispatcher) {
        val (session, solves) = createSessionWithSolves(
            name = "Sync-Selection-Session",
            durationsMs = (0 until 10).map { 10000L + it * 500 }
        )

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.expandSession(session.id)
        advanceUntilIdle()

        // User selects solves 0, 1, 2, 3, 4
        viewModel.startSelection(solves[0].id)
        for (i in 1..4) {
            viewModel.toggleSolveSelection(solves[i].id)
        }
        advanceUntilIdle()

        assertEquals(5, viewModel.uiState.value.selectedSolveIds.size)
        assertTrue(viewModel.uiState.value.isSelectionMode)

        // Simultaneously:
        // 1. Background sync marks solves 1 and 2 as soft-deleted in Room (as if deleted on another device)
        // 2. User toggles selection of solve 5 and solve 6
        // 3. User queries getSelectedSolves()
        val syncRemoteDeleteJob = launch {
            val nowIso = Instant.now().toString()
            database.solveDao().softDelete(solves[1].id, deletedAt = nowIso, updatedAt = nowIso)
            database.solveDao().softDelete(solves[2].id, deletedAt = nowIso, updatedAt = nowIso)
        }

        val userSelectJob = launch {
            viewModel.toggleSolveSelection(solves[5].id)
            viewModel.toggleSolveSelection(solves[6].id)
        }

        listOf(syncRemoteDeleteJob, userSelectJob).forEach { it.join() }
        advanceUntilIdle()

        // Verify getSelectedSolves() filters out remote-deleted solves without throwing ConcurrentModificationException
        val selectedActiveSolves = viewModel.getSelectedSolves()
        val selectedActiveIds = selectedActiveSolves.map { it.id }.toSet()

        // Remote-deleted solves 1 and 2 must NOT be returned among active selected solves!
        assertFalse(selectedActiveIds.contains(solves[1].id))
        assertFalse(selectedActiveIds.contains(solves[2].id))
        assertTrue(selectedActiveIds.contains(solves[0].id))
        assertTrue(selectedActiveIds.contains(solves[3].id))
        assertTrue(selectedActiveIds.contains(solves[4].id))
        assertTrue(selectedActiveIds.contains(solves[5].id))
        assertTrue(selectedActiveIds.contains(solves[6].id))

        // Execute batch delete of the remaining selected solves
        viewModel.deleteSelectedSolves()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isSelectionMode)
        assertTrue(viewModel.uiState.value.selectedSolveIds.isEmpty())

        // In DB, only solves 7, 8, 9 remain active
        val remainingInDb = database.solveDao().getSolvesBySession("guest", session.id)
        assertEquals(3, remainingInDb.size)
        assertEquals(setOf(solves[7].id, solves[8].id, solves[9].id), remainingInDb.map { it.id }.toSet())
    }

    // =============================================================================================
    // Challenge 3: Empirical Bug Verification — Ghost Selection on Single Solve Deletion
    // =============================================================================================
    @Test
    fun testGhostSelectionVulnerabilityOnSingleSolveDelete() = runTest(testDispatcher) {
        val (session, solves) = createSessionWithSolves(
            name = "Ghost-Selection-Session",
            durationsMs = listOf(11000L, 12000L)
        )

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.expandSession(session.id)
        advanceUntilIdle()

        // Select solve 0
        viewModel.startSelection(solves[0].id)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isSelectionMode)
        assertEquals(setOf(solves[0].id), viewModel.uiState.value.selectedSolveIds)

        // Delete solve 0 via single solve delete (e.g. clicking delete button on solve card)
        viewModel.deleteSolve(solves[0].toSolveTime())
        advanceUntilIdle()

        val remainingSelected = viewModel.uiState.value.selectedSolveIds
        val isSelectionMode = viewModel.uiState.value.isSelectionMode

        // EMPIRICAL BUG CHECK:
        // Does deleteSolve purge the deleted solve from selectedSolveIds?
        val hasGhostSelectionBug = remainingSelected.contains(solves[0].id)
        if (hasGhostSelectionBug) {
            println("[VULNERABILITY REPRODUCED] Ghost selection in deleteSolve: selectedSolveIds=$remainingSelected, isSelectionMode=$isSelectionMode")
        }

        // If this assertion fails, it empirically confirms the bug in HistoryViewModel.deleteSolve
        assertEquals(
            "deleteSolve must purge the deleted solve from selectedSolveIds",
            emptySet<String>(),
            remainingSelected
        )
        assertFalse(
            "Selection mode must be dismissed when the only selected solve was deleted",
            isSelectionMode
        )
    }

    // =============================================================================================
    // Challenge 4: Empirical Bug Verification — Cache Desynchronization on Single and Batch Undo
    // =============================================================================================
    @Test
    fun testCacheDesynchronizationVulnerabilityOnUndo() = runTest(testDispatcher) {
        val (session, solves) = createSessionWithSolves(
            name = "Undo-Cache-Session",
            durationsMs = listOf(15000L, 16000L)
        )

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.expandSession(session.id)
        advanceUntilIdle()

        val initialGroup = viewModel.uiState.value.sessionGroups.first { it.id == session.id }
        assertEquals(2, initialGroup.solves.size)

        // Delete solve 0
        viewModel.deleteSolve(solves[0].toSolveTime())
        advanceUntilIdle()

        val afterDeleteGroup = viewModel.uiState.value.sessionGroups.first { it.id == session.id }
        assertEquals(1, afterDeleteGroup.solves.size)

        // Restore solve 0 via undo
        viewModel.undoDelete()
        advanceUntilIdle()

        val afterUndoGroup = viewModel.uiState.value.sessionGroups.first { it.id == session.id }

        // EMPIRICAL BUG CHECK:
        // Does restoreSolve / undoDelete update _sessionSolvesCache so the expanded card shows the restored solve?
        val hasCacheDesyncBug = afterUndoGroup.solves.size != 2
        if (hasCacheDesyncBug) {
            println("[VULNERABILITY REPRODUCED] Cache desynchronization on undo: expected 2 solves in expanded group, found ${afterUndoGroup.solves.size}")
        }

        assertEquals(
            "Expanded session group must contain the restored solve after undoDelete",
            2,
            afterUndoGroup.solves.size
        )
    }

    // =============================================================================================
    // Challenge 5: Session Cascade Deletion and Restoration Under Active UI Observation
    // =============================================================================================
    @Test
    fun testCascadeSessionDeleteAndRestoreUnderActiveObservation() = runTest(testDispatcher) {
        val (session, solves) = createSessionWithSolves(
            name = "Cascade-Observed-Session",
            durationsMs = (1..50).map { 10000L + it * 100 }
        )

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.expandSession(session.id)
        advanceUntilIdle()

        val initialGroup = viewModel.uiState.value.sessionGroups.first { it.id == session.id }
        assertEquals(50, initialGroup.solveCount)
        assertEquals(50, initialGroup.solves.size)

        // Delete session with 50 solves
        viewModel.deleteSession(session)
        advanceUntilIdle()

        // Session must be completely removed from uiState.sessionGroups and expandedSessionIds
        assertTrue(viewModel.uiState.value.sessionGroups.none { it.id == session.id })
        assertFalse(viewModel.uiState.value.expandedSessionIds.contains(session.id))
        assertFalse(getInternalExpandedJobs(viewModel).containsKey(session.id))

        // In DB, all 50 solves and the session are marked deleted
        assertEquals(0, database.sessionDao().getAllActiveSessionsForOwner("guest").size)
        assertEquals(0, database.solveDao().getSolvesBySession("guest", session.id).size)

        // Undo session deletion
        viewModel.undoDeleteSession()
        advanceUntilIdle()

        // Session must be restored
        val restoredGroups = viewModel.uiState.value.sessionGroups
        assertEquals(1, restoredGroups.size)
        val restoredGroup = restoredGroups.first()
        assertEquals(session.id, restoredGroup.id)
        assertEquals(50, restoredGroup.solveCount)
        assertNotNull(restoredGroup.bestDurationMs)
        assertNotNull(restoredGroup.avgDurationMs)

        // In DB, session and 50 solves are active
        assertEquals(1, database.sessionDao().getAllActiveSessionsForOwner("guest").size)
        assertEquals(50, database.solveDao().getSolvesBySession("guest", session.id).size)
    }

    // Fake Auth Manager
    private class FakeAuthManager : AuthManager {
        private val _authState = MutableStateFlow<AuthState>(AuthState.Guest)
        override val authState: StateFlow<AuthState> = _authState.asStateFlow()
        override var currentUser: User? = null

        override suspend fun initialize() = Unit
        override suspend fun register(email: String, password: String): AuthResult<Unit> = AuthResult.Success(Unit)
        override suspend fun login(email: String, password: String): AuthResult<User> = AuthResult.Success(User(id = "u1", email = "u@test.com"))
        override suspend fun loginWithGoogle(idToken: String): AuthResult<User> = AuthResult.Success(User(id = "u1", email = "u@test.com"))
        override suspend fun verifyEmail(token: String): AuthResult<User> = AuthResult.Success(User(id = "u1", email = "u@test.com"))
        override suspend fun resendVerificationEmail(email: String): AuthResult<Unit> = AuthResult.Success(Unit)
        override suspend fun requestPasswordReset(email: String): AuthResult<Unit> = AuthResult.Success(Unit)
        override suspend fun resetPassword(token: String, newPassword: String): AuthResult<User> = AuthResult.Success(User(id = "u1", email = "u@test.com"))
        override suspend fun refreshSession(): AuthResult<User> = AuthResult.Success(User(id = "u1", email = "u@test.com"))
        override suspend fun logout(): AuthResult<Unit> = AuthResult.Success(Unit)
        override suspend fun adoptGuestData(userId: String) = Unit
    }
}
