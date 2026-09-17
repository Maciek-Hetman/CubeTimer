package com.maciekhetman.cubetimer.ui.screens

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.data.SolvesRepository
import com.maciekhetman.cubetimer.data.auth.AuthManager
import com.maciekhetman.cubetimer.data.auth.AuthResult
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.local.entity.SessionEntity
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import com.maciekhetman.cubetimer.data.session.SessionManagerImpl
import com.maciekhetman.cubetimer.data.session.SessionRepositoryImpl
import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Penalty
import com.maciekhetman.cubetimer.model.Session
import com.maciekhetman.cubetimer.model.SessionKind
import com.maciekhetman.cubetimer.model.SolveTime
import com.maciekhetman.cubetimer.model.User
import com.maciekhetman.cubetimer.viewmodel.HistoryUiEffect
import com.maciekhetman.cubetimer.viewmodel.HistoryViewModel
import com.maciekhetman.cubetimer.viewmodel.PenaltyFilter
import com.maciekhetman.cubetimer.viewmodel.PuzzleScope
import com.maciekhetman.cubetimer.viewmodel.SessionKindFilter
import com.maciekhetman.cubetimer.viewmodel.SessionSortOrder
import com.maciekhetman.cubetimer.viewmodel.SolveSortOrder
import com.maciekhetman.cubetimer.viewmodel.TimeRangeFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.joinAll
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
import java.util.UUID

/**
 * Adversarial Concurrency and Stress Verification Suite for Milestone 4 History UI.
 *
 * Challenges:
 * 1. Concurrency between session expansion, child solve loading, and filter resets.
 * 2. Rapid selection/deselection toggles and batch delete operations with undo.
 * 3. Empty states and single-solve sessions.
 * 4. Large session lists (100+ items) with high-volume sorting performance.
 * 5. Ghost selection tracking when a selected solve is deleted via single-solve deletion.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HistoryConcurrencyAndStressChallengeTest {

    private lateinit var testDispatcher: TestDispatcher
    private lateinit var application: Application
    private lateinit var database: CubeDatabase
    private lateinit var solvesRepository: SolvesRepository
    private lateinit var sessionRepository: SessionRepositoryImpl
    private lateinit var sessionManager: SessionManagerImpl
    private lateinit var fakeAuthManager: FakeAuthManager

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
            syncOutboxDao = database.syncOutboxDao()
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

    // =========================================================================
    // 1. CONCURRENCY: RAPID EXPANSION, CHILD SOLVE LOADING & FILTER RESETS
    // =========================================================================

    @Test
    fun testConcurrentExpansionAndFilterResets_noDeadlockOrInconsistentState() = runTest(testDispatcher) {
        // Seed 15 sessions with 5 solves each in Room
        val sessionIds = mutableListOf<String>()
        for (i in 0 until 15) {
            val sId = "sess-conc-$i"
            sessionIds.add(sId)
            database.sessionDao().insert(
                SessionEntity(
                    id = sId,
                    ownerId = "guest",
                    name = "Session $i",
                    event = "3x3",
                    kind = if (i % 2 == 0) "manual" else "automatic",
                    startedAt = "2026-09-12T10:${String.format("%02d", i)}:00Z"
                )
            )
            for (j in 0 until 5) {
                database.solveDao().insert(
                    SolveEntity(
                        id = "solve-conc-$i-$j",
                        ownerId = "guest",
                        sessionId = sId,
                        event = "3x3",
                        durationMs = 10000L + (i * 100) + (j * 10),
                        penalty = if (j == 3) "plus_two" else if (j == 4) "dnf" else "none",
                        solvedAt = "2026-09-12T10:${String.format("%02d", i)}:${String.format("%02d", j * 10)}Z",
                        scramble = "R U R' U'",
                        version = 0L
                    )
                )
            }
        }

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Concurrently hammer expansion toggles and filter changes
        val expansionJob = launch {
            for (round in 0 until 30) {
                val targetId = sessionIds[round % sessionIds.size]
                viewModel.toggleSessionExpanded(targetId)
            }
        }

        val filterJob = launch {
            for (round in 0 until 20) {
                when (round % 5) {
                    0 -> viewModel.setSessionSort(SessionSortOrder.NAME_ASC)
                    1 -> viewModel.setSolveSort(SolveSortOrder.LOWEST_TIME)
                    2 -> viewModel.setPenaltyFilter(PenaltyFilter.CLEAN_ONLY)
                    3 -> viewModel.resetAllFilters()
                    4 -> viewModel.setPuzzleScope(PuzzleScope.ALL_PUZZLES)
                }
            }
        }

        joinAll(expansionJob, filterJob)
        advanceUntilIdle()

        val finalState = viewModel.uiState.value
        assertNotNull(finalState)
        assertEquals(15, finalState.sessionGroups.size)
        assertFalse(finalState.isLoading)

        // Reset all filters to establish a clean known state
        viewModel.resetAllFilters()
        advanceUntilIdle()

        val cleanState = viewModel.uiState.value
        assertEquals(15, cleanState.sessionGroups.size)
        // Verify that any expanded session accurately loaded all 5 solves without corruption
        cleanState.sessionGroups.filter { it.isExpanded }.forEach { group ->
            assertFalse(group.isSolvesLoading)
            assertEquals(5, group.solves.size)
        }
    }

    // =========================================================================
    // 2. RAPID SELECTION TOGGLES & BATCH DELETION WITH UNDO
    // =========================================================================

    @Test
    fun testRapidSelectionTogglesAndBatchDeleteWithUndo() = runTest(testDispatcher) {
        val sId1 = "batch-sess-1"
        val sId2 = "batch-sess-2"
        database.sessionDao().insert(
            SessionEntity(id = sId1, ownerId = "guest", name = "Batch S1", event = "3x3", kind = "manual", startedAt = "2026-09-12T10:00:00Z")
        )
        database.sessionDao().insert(
            SessionEntity(id = sId2, ownerId = "guest", name = "Batch S2", event = "3x3", kind = "manual", startedAt = "2026-09-12T10:01:00Z")
        )

        val solvesS1 = (0 until 4).map { j ->
            SolveEntity(
                id = "s1-solve-$j",
                ownerId = "guest",
                sessionId = sId1,
                event = "3x3",
                durationMs = 9000L + j * 50,
                penalty = "none",
                solvedAt = "2026-09-12T10:00:${String.format("%02d", j * 10)}Z",
                scramble = "R U",
                version = 0L
            )
        }
        val solvesS2 = (0 until 4).map { j ->
            SolveEntity(
                id = "s2-solve-$j",
                ownerId = "guest",
                sessionId = sId2,
                event = "3x3",
                durationMs = 11000L + j * 50,
                penalty = "none",
                solvedAt = "2026-09-12T10:01:${String.format("%02d", j * 10)}Z",
                scramble = "L F",
                version = 0L
            )
        }
        database.solveDao().insertAll(solvesS1 + solvesS2)

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Expand both sessions
        viewModel.expandSession(sId1)
        viewModel.expandSession(sId2)
        advanceUntilIdle()

        var state = viewModel.uiState.value
        assertEquals(2, state.sessionGroups.size)
        val g1 = state.sessionGroups.find { it.session.id == sId1 }!!
        val g2 = state.sessionGroups.find { it.session.id == sId2 }!!
        assertEquals(4, g1.solves.size)
        assertEquals(4, g2.solves.size)

        // Rapid selection toggles: start selection on s1-solve-0
        viewModel.startSelection("s1-solve-0")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isSelectionMode)
        assertEquals(setOf("s1-solve-0"), viewModel.uiState.value.selectedSolveIds)

        // Rapidly toggle s1-solve-1 on, then off, then on again
        viewModel.toggleSolveSelection("s1-solve-1")
        advanceUntilIdle()
        assertEquals(setOf("s1-solve-0", "s1-solve-1"), viewModel.uiState.value.selectedSolveIds)
        viewModel.toggleSolveSelection("s1-solve-1")
        advanceUntilIdle()
        assertEquals(setOf("s1-solve-0"), viewModel.uiState.value.selectedSolveIds)
        viewModel.toggleSolveSelection("s1-solve-1")
        advanceUntilIdle()
        assertEquals(setOf("s1-solve-0", "s1-solve-1"), viewModel.uiState.value.selectedSolveIds)

        // Select across sessions: add s2-solve-0 and s2-solve-1
        viewModel.toggleSolveSelection("s2-solve-0")
        viewModel.toggleSolveSelection("s2-solve-1")
        advanceUntilIdle()
        assertEquals(4, viewModel.uiState.value.selectedSolveIds.size)

        // Trigger batch deletion
        viewModel.deleteSelectedSolves()
        advanceUntilIdle()

        // Verify selection mode is dismissed immediately
        assertFalse(viewModel.uiState.value.isSelectionMode)
        assertTrue(viewModel.uiState.value.selectedSolveIds.isEmpty())

        // Verify solves are deleted from Room
        val remainingSolves = database.solveDao().getSolvesByScope("guest", null, null)
        assertEquals(4, remainingSolves.size)
        val remainingIds = remainingSolves.map { it.id }.toSet()
        assertEquals(setOf("s1-solve-2", "s1-solve-3", "s2-solve-2", "s2-solve-3"), remainingIds)

        // Verify SessionGroupUiModels updated their solve counts in Room
        val updatedG1 = viewModel.uiState.value.sessionGroups.find { it.session.id == sId1 }!!
        val updatedG2 = viewModel.uiState.value.sessionGroups.find { it.session.id == sId2 }!!
        assertEquals(2, updatedG1.solves.size)
        assertEquals(2, updatedG2.solves.size)

        // Undo batch deletion
        viewModel.undoDeleteBatch()
        advanceUntilIdle()

        // Verify Room restored all 8 solves
        val restoredSolves = database.solveDao().getSolvesByScope("guest", null, null)
        assertEquals(8, restoredSolves.size)
    }

    // =========================================================================
    // 3. EMPTY STATES & SINGLE SOLVE SESSIONS
    // =========================================================================

    @Test
    fun testEmptyState_rendersZeroSessionsAndHandlesFiltersWithoutCrashing() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.sessionGroups.isEmpty())
        assertEquals(0, state.totalCount)
        assertFalse(state.isLoading)

        // Filter operations on empty state should be completely safe
        viewModel.setSessionSort(SessionSortOrder.MOST_SOLVES)
        viewModel.setSolveSort(SolveSortOrder.HIGHEST_TIME)
        viewModel.setPenaltyFilter(PenaltyFilter.DNF_ONLY)
        viewModel.setTimeRangeFilter(10000L, 20000L)
        advanceUntilIdle()

        val filteredState = viewModel.uiState.value
        assertTrue(filteredState.sessionGroups.isEmpty())
        assertEquals(4, filteredState.totalActiveFilterCount)

        viewModel.resetAllFilters()
        advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.totalActiveFilterCount)
    }

    @Test
    fun testSingleSolveSession_expandDeleteAndUndoRestoration() = runTest(testDispatcher) {
        val sId = "single-solve-sess"
        database.sessionDao().insert(
            SessionEntity(id = sId, ownerId = "guest", name = "Solo Session", event = "3x3", kind = "manual", startedAt = "2026-09-12T10:00:00Z")
        )
        val singleSolve = SolveEntity(
            id = "only-solve",
            ownerId = "guest",
            sessionId = sId,
            event = "3x3",
            durationMs = 8200L,
            penalty = "none",
            solvedAt = "2026-09-12T10:00:00Z",
            scramble = "R U R' U'",
            version = 0L
        )
        database.solveDao().insert(singleSolve)

        val viewModel = createViewModel()
        advanceUntilIdle()

        val group = viewModel.uiState.value.sessionGroups.first()
        assertEquals(1, group.solveCount)
        assertEquals(8200L, group.bestDurationMs)
        assertEquals(8200L, group.avgDurationMs)

        viewModel.expandSession(sId)
        advanceUntilIdle()

        val expandedGroup = viewModel.uiState.value.sessionGroups.first()
        assertEquals(1, expandedGroup.solves.size)
        assertEquals("only-solve", expandedGroup.solves.first().id)

        // Delete the single solve via deleteSolve
        val solveDomain = expandedGroup.solves.first()
        viewModel.deleteSolve(solveDomain)
        advanceUntilIdle()

        // Session still exists, but solveCount is 0 in Room
        val emptySessionGroup = viewModel.uiState.value.sessionGroups.first()
        assertEquals(0, emptySessionGroup.solveCount)
        assertTrue(emptySessionGroup.solves.isEmpty())

        // Undo restoration
        viewModel.restoreSolve(solveDomain)
        advanceUntilIdle()

        val restoredGroup = viewModel.uiState.value.sessionGroups.first()
        assertEquals(1, restoredGroup.solves.size)
        assertEquals("only-solve", restoredGroup.solves.first().id)
    }

    // =========================================================================
    // 4. LARGE SESSION LIST (120 SESSIONS) & HIGH VOLUME SORTING
    // =========================================================================

    @Test
    fun testLargeSessionList_120Sessions_sortsCorrectlyAndQuickly() = runTest(testDispatcher) {
        val count = 120
        val entities = (0 until count).map { i ->
            SessionEntity(
                id = "large-sess-$i",
                ownerId = "guest",
                name = "Session ${String.format("%03d", (count - i))}", // Reverse alphabetical
                event = if (i % 2 == 0) "3x3" else "2x2",
                kind = if (i % 3 == 0) "manual" else "automatic",
                startedAt = "2026-09-12T10:${String.format("%02d", i / 60)}:${String.format("%02d", i % 60)}Z"
            )
        }
        database.sessionDao().insertAll(entities)

        // Seed 1 solve for the first 30 sessions
        val solves = (0 until 30).map { i ->
            SolveEntity(
                id = "large-solve-$i",
                ownerId = "guest",
                sessionId = "large-sess-$i",
                event = if (i % 2 == 0) "3x3" else "2x2",
                durationMs = 10000L + i * 100,
                penalty = "none",
                solvedAt = "2026-09-12T10:00:00Z",
                scramble = "R U",
                version = 0L
            )
        }
        database.solveDao().insertAll(solves)

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Under ACTIVE_PUZZLE ("3x3"), 60 sessions match
        assertEquals(60, viewModel.uiState.value.sessionGroups.size)

        // Switch to ALL_PUZZLES to expose all 120 sessions
        viewModel.setPuzzleScope(PuzzleScope.ALL_PUZZLES)
        advanceUntilIdle()

        assertEquals(120, viewModel.uiState.value.sessionGroups.size)

        // Measure sorting time across 120 sessions
        val startTime = System.currentTimeMillis()

        // 1. Sort Name ASC
        viewModel.setSessionSort(SessionSortOrder.NAME_ASC)
        advanceUntilIdle()
        val nameAscGroups = viewModel.uiState.value.sessionGroups
        assertEquals("Session 001", nameAscGroups.first().name)
        assertEquals("Session 120", nameAscGroups.last().name)

        // 2. Sort Name DESC
        viewModel.setSessionSort(SessionSortOrder.NAME_DESC)
        advanceUntilIdle()
        val nameDescGroups = viewModel.uiState.value.sessionGroups
        assertEquals("Session 120", nameDescGroups.first().name)
        assertEquals("Session 001", nameDescGroups.last().name)

        // 3. Sort Most Solves
        viewModel.setSessionSort(SessionSortOrder.MOST_SOLVES)
        advanceUntilIdle()
        val mostSolvesGroups = viewModel.uiState.value.sessionGroups
        assertTrue(mostSolvesGroups.first().solveCount >= mostSolvesGroups.last().solveCount)
        assertEquals(1, mostSolvesGroups.first().solveCount)
        assertEquals(0, mostSolvesGroups.last().solveCount)

        val duration = System.currentTimeMillis() - startTime
        assertTrue("Sorting 120 sessions should take less than 1000ms", duration < 1000)
    }

    // =========================================================================
    // 5. EMPIRICAL DISCOVERY: GHOST SELECTION TRACKING ON SINGLE SOLVE DELETE
    // =========================================================================

    @Test
    fun testSingleSolveDelete_whenSolveIsSelected_reproducesOrPurgesGhostSelection() = runTest(testDispatcher) {
        val sId = "ghost-test-session"
        database.sessionDao().insert(
            SessionEntity(id = sId, ownerId = "guest", name = "Ghost Test", event = "3x3", kind = "manual", startedAt = "2026-09-12T10:00:00Z")
        )
        val solve1 = SolveEntity(
            id = "ghost-solve-1",
            ownerId = "guest",
            sessionId = sId,
            event = "3x3",
            durationMs = 12000L,
            penalty = "none",
            solvedAt = "2026-09-12T10:00:00Z",
            scramble = "R U",
            version = 0L
        )
        val solve2 = SolveEntity(
            id = "ghost-solve-2",
            ownerId = "guest",
            sessionId = sId,
            event = "3x3",
            durationMs = 13000L,
            penalty = "none",
            solvedAt = "2026-09-12T10:01:00Z",
            scramble = "R' U'",
            version = 0L
        )
        database.solveDao().insertAll(listOf(solve1, solve2))

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.expandSession(sId)
        advanceUntilIdle()

        val domainSolves = viewModel.uiState.value.sessionGroups.first().solves
        assertEquals(2, domainSolves.size)

        // Select solve 1
        viewModel.startSelection("ghost-solve-1")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isSelectionMode)
        assertEquals(setOf("ghost-solve-1"), viewModel.uiState.value.selectedSolveIds)

        // Delete solve 1 via deleteSolve (inline trash click)
        val targetSolve = domainSolves.find { it.id == "ghost-solve-1" }!!
        viewModel.deleteSolve(targetSolve)
        advanceUntilIdle()

        // VERIFY: Does selectedSolveIds still contain "ghost-solve-1"?
        val remainingSelected = viewModel.uiState.value.selectedSolveIds
        val isStillSelectionMode = viewModel.uiState.value.isSelectionMode

        // Check if ghost-solve-1 remains in selectedSolveIds
        val hasGhostSelectionBug = remainingSelected.contains("ghost-solve-1")
        if (hasGhostSelectionBug) {
            println("[VULNERABILITY CONFIRMED] deleteSolve() did not remove the deleted solve from selectedSolveIds. Selection mode is still active: $isStillSelectionMode with orphan ID: $remainingSelected")
        }
        
        // Assert for regression/robustness documentation
        // If bug is present, this confirms empirical reproduction
        assertEquals(
            "Empirical Bug Check: deleteSolve should purge the deleted solve from selectedSolveIds",
            emptySet<String>(),
            remainingSelected
        )
    }

    // --- Fake Auth Manager Helper ---
    private class FakeAuthManager : AuthManager {
        private val _authState = MutableStateFlow<AuthState>(AuthState.Guest)
        override val authState: StateFlow<AuthState> = _authState.asStateFlow()
        override var currentUser: User? = null

        override suspend fun initialize() = Unit
        override suspend fun register(email: String, password: String): AuthResult<Unit> = AuthResult.Success(Unit)
        override suspend fun login(email: String, password: String): AuthResult<User> = AuthResult.Success(User(id = "u1", email = email))
        override suspend fun loginWithGoogle(idToken: String): AuthResult<User> = AuthResult.Success(User(id = "u1", email = "u@test.com"))
        override suspend fun verifyEmail(token: String): AuthResult<User> = AuthResult.Success(User(id = "u1", email = "u@test.com"))
        override suspend fun resendVerificationEmail(email: String): AuthResult<Unit> = AuthResult.Success(Unit)
        override suspend fun requestPasswordReset(email: String): AuthResult<Unit> = AuthResult.Success(Unit)
        override suspend fun resetPassword(token: String, newPassword: String): AuthResult<User> = AuthResult.Success(User(id = "u1", email = "u@test.com"))
        override suspend fun refreshSession(): AuthResult<User> = AuthResult.Success(User(id = "u1", email = "u@test.com"))
        override suspend fun logout(): AuthResult<Unit> {
            _authState.value = AuthState.Guest
            return AuthResult.Success(Unit)
        }
        override suspend fun adoptGuestData(userId: String) = Unit
    }
}
