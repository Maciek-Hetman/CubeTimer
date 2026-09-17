package com.maciekhetman.cubetimer.viewmodel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.maciekhetman.cubetimer.data.SolvesRepository
import com.maciekhetman.cubetimer.data.auth.AuthManager
import com.maciekhetman.cubetimer.data.auth.AuthResult
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.local.converter.CubeTypeConverters
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import com.maciekhetman.cubetimer.data.local.mapper.toSolveTime
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
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Adversarial empirical challenge test suite for HistoryViewModel verifying:
 * 1. Rapid concurrent session expansion and collapse stress (100+ toggles) ensuring child jobs cancel cleanly.
 * 2. Multi-session simultaneous expansion stress and cache isolation without cross-contamination.
 * 3. Multi-selection race conditions (concurrent selection, solve deletion, and insertion).
 * 4. Interleaved batch deletion, single solve deletion, session deletion, and multi-undo race.
 * 5. Select all under active filters (verifying un-selected invisible items are NOT deleted).
 * 6. Select all toggle behavior and deselect-all cycle under active filtering.
 * 7. Rapid filter and sort barrage under continuous StateFlow emissions.
 * 8. Authentication switch under active expansion and selection (flushing cache and cancelling jobs).
 * 9. Undo idempotency and double-undo stress across batch, single, session, and clear-all actions.
 * 10. SAF CSV export edge cases under empty selection and zero-solve sessions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HistoryViewModelStressChallengeTest {

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
        durationsMs: List<Long> = listOf(10000L, 12000L, 15000L),
        penalties: List<String> = emptyList(),
        baseTime: Instant = Instant.parse("2026-08-30T10:00:00.000Z")
    ): Pair<Session, List<SolveEntity>> {
        val session = sessionRepository.createManualSession(name, mode, ownerId)
        val entities = durationsMs.mapIndexed { index, duration ->
            val penalty = if (index < penalties.size) penalties[index] else "none"
            SolveEntity(
                id = "${session.id}-solve-$index",
                ownerId = ownerId,
                sessionId = session.id,
                event = CubeTypeConverters.fromMode(mode),
                durationMs = duration,
                penalty = penalty,
                solvedAt = baseTime.plus(index.toLong(), ChronoUnit.MINUTES).toString(),
                scramble = "R U R' U' move-$index",
                version = 0L
            )
        }
        database.solveDao().insertAll(entities)
        return Pair(session, entities)
    }

    @Suppress("UNCHECKED_CAST")
    private fun getExpandedJobs(vm: HistoryViewModel): Map<String, Job> {
        val field = HistoryViewModel::class.java.getDeclaredField("expandedSessionJobs")
        field.isAccessible = true
        return field.get(vm) as Map<String, Job>
    }

    // ---------------------------------------------------------------------------------------------
    // Challenge Vector 1: Rapid Concurrent Session Expansion and Collapse (100+ Toggles)
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testRapidSessionExpandCollapse100TogglesStress() = runTest(testDispatcher) {
        val (s0, _) = createSessionWithSolves("Session-0", durationsMs = listOf(10000L, 11000L))
        val (s1, _) = createSessionWithSolves("Session-1", durationsMs = listOf(12000L, 13000L, 14000L))
        val (s2, _) = createSessionWithSolves("Session-2", durationsMs = listOf(15000L))
        val (s3, _) = createSessionWithSolves("Session-3", durationsMs = listOf(16000L, 17000L))
        val (s4, _) = createSessionWithSolves("Session-4", durationsMs = listOf(18000L, 19000L, 20000L, 21000L))

        viewModel = createViewModel()
        advanceUntilIdle()

        // Verify initial state: all 5 sessions present, none expanded
        assertEquals(5, viewModel.uiState.value.sessionGroups.size)
        assertTrue(viewModel.uiState.value.expandedSessionIds.isEmpty())

        // Stress: Launch 222 toggles across 5 sessions concurrently:
        // S0: 20 toggles (even -> ends collapsed)
        // S1: 21 toggles (odd  -> ends expanded)
        // S2: 40 toggles (even -> ends collapsed)
        // S3: 41 toggles (odd  -> ends expanded)
        // S4: 100 toggles (even -> ends collapsed)
        val j0 = launch { repeat(20) { viewModel.toggleSessionExpanded(s0.id) } }
        val j1 = launch { repeat(21) { viewModel.toggleSessionExpanded(s1.id) } }
        val j2 = launch { repeat(40) { viewModel.toggleSessionExpanded(s2.id) } }
        val j3 = launch { repeat(41) { viewModel.toggleSessionExpanded(s3.id) } }
        val j4 = launch { repeat(100) { viewModel.toggleSessionExpanded(s4.id) } }

        listOf(j0, j1, j2, j3, j4).forEach { it.join() }
        advanceUntilIdle()

        val state = viewModel.uiState.value
        val expectedExpanded = setOf(s1.id, s3.id)
        assertEquals(expectedExpanded, state.expandedSessionIds)
        assertEquals(expectedExpanded, viewModel.expandedSessionIds.value)

        val groupMap = state.sessionGroups.associateBy { it.id }

        // S0: Collapsed, child list empty, job removed/cancelled
        val g0 = groupMap[s0.id]!!
        assertFalse(g0.isExpanded)
        assertTrue(g0.solves.isEmpty())
        assertFalse(g0.isSolvesLoading)

        // S1: Expanded, child list contains exact 3 solves
        val g1 = groupMap[s1.id]!!
        assertTrue(g1.isExpanded)
        assertEquals(3, g1.solves.size)
        assertEquals(listOf("${s1.id}-solve-2", "${s1.id}-solve-1", "${s1.id}-solve-0"), g1.solves.map { it.id })
        assertFalse(g1.isSolvesLoading)

        // S2: Collapsed
        val g2 = groupMap[s2.id]!!
        assertFalse(g2.isExpanded)
        assertTrue(g2.solves.isEmpty())

        // S3: Expanded, child list contains exact 2 solves
        val g3 = groupMap[s3.id]!!
        assertTrue(g3.isExpanded)
        assertEquals(2, g3.solves.size)
        assertFalse(g3.isSolvesLoading)

        // S4: Collapsed after 100 rapid toggles
        val g4 = groupMap[s4.id]!!
        assertFalse(g4.isExpanded)
        assertTrue(g4.solves.isEmpty())

        // Empirical assertion on internal job table: only S1 and S3 remain in expandedSessionJobs
        val activeJobs = getExpandedJobs(viewModel)
        assertEquals(setOf(s1.id, s3.id), activeJobs.keys)
        assertTrue(activeJobs[s1.id]?.isActive == true)
        assertTrue(activeJobs[s3.id]?.isActive == true)
    }

    // ---------------------------------------------------------------------------------------------
    // Challenge Vector 2: Multi-Session Expansion Stress and Dynamic Solve Injection
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testConcurrentMultiSessionExpansionWithConcurrentSolveInsertion() = runTest(testDispatcher) {
        val sessionCount = 8
        val sessions = mutableListOf<Session>()
        for (i in 0 until sessionCount) {
            sessions.add(
                createSessionWithSolves(
                    name = "Multi-Session-$i",
                    durationsMs = listOf(10000L + i * 100, 11000L + i * 100, 12000L + i * 100)
                ).first
            )
        }

        viewModel = createViewModel()
        advanceUntilIdle()

        // Concurrently expand all 8 sessions
        sessions.forEach { s ->
            launch { viewModel.expandSession(s.id) }
        }

        // Concurrently insert new solves into sessions S0, S2, S5
        launch {
            val extraSolves = listOf(
                SolveEntity(
                    id = "extra-s0-1",
                    ownerId = "guest",
                    sessionId = sessions[0].id,
                    event = "3x3",
                    durationMs = 9500L,
                    penalty = "none",
                    solvedAt = "2026-08-30T11:00:00.000Z",
                    scramble = "U2",
                    version = 0L
                ),
                SolveEntity(
                    id = "extra-s0-2",
                    ownerId = "guest",
                    sessionId = sessions[0].id,
                    event = "3x3",
                    durationMs = 9600L,
                    penalty = "none",
                    solvedAt = "2026-08-30T11:01:00.000Z",
                    scramble = "U2",
                    version = 0L
                ),
                SolveEntity(
                    id = "extra-s2-1",
                    ownerId = "guest",
                    sessionId = sessions[2].id,
                    event = "3x3",
                    durationMs = 8800L,
                    penalty = "none",
                    solvedAt = "2026-08-30T11:02:00.000Z",
                    scramble = "U2",
                    version = 0L
                ),
                SolveEntity(
                    id = "extra-s5-1",
                    ownerId = "guest",
                    sessionId = sessions[5].id,
                    event = "3x3",
                    durationMs = 7700L,
                    penalty = "none",
                    solvedAt = "2026-08-30T11:03:00.000Z",
                    scramble = "U2",
                    version = 0L
                )
            )
            database.solveDao().insertAll(extraSolves)
        }

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(8, state.sessionGroups.size)
        assertEquals(8, state.expandedSessionIds.size)

        val groups = state.sessionGroups.associateBy { it.id }

        // S0 had 3 initial + 2 dynamic = 5
        assertEquals(5, groups[sessions[0].id]?.solves?.size)
        // S2 had 3 initial + 1 dynamic = 4
        assertEquals(4, groups[sessions[2].id]?.solves?.size)
        // S5 had 3 initial + 1 dynamic = 4
        assertEquals(4, groups[sessions[5].id]?.solves?.size)
        // S1 had 3 initial = 3
        assertEquals(3, groups[sessions[1].id]?.solves?.size)

        // Verify strict cache isolation: every solve belongs to its containing session
        groups.values.forEach { group ->
            group.solves.forEach { solve ->
                assertEquals(group.id, solve.sessionId)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Challenge Vector 3: Multi-Selection Race Conditions (Concurrent Selection & Deletion)
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testMultiSelectionRacesConcurrentDeletionAndRestoration() = runTest(testDispatcher) {
        val (session, solves) = createSessionWithSolves(
            name = "Selection-Race-Session",
            durationsMs = (0 until 8).map { 10000L + it * 1000 }
        )

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.expandSession(session.id)
        advanceUntilIdle()

        // Select solve-0, solve-1, solve-2, solve-3
        viewModel.startSelection(solves[0].id)
        viewModel.toggleSolveSelection(solves[1].id)
        viewModel.toggleSolveSelection(solves[2].id)
        viewModel.toggleSolveSelection(solves[3].id)
        advanceUntilIdle()

        val initialSelected = viewModel.getSelectedSolves()
        assertEquals(4, initialSelected.size)
        assertTrue(viewModel.uiState.value.isSelectionMode)

        // Concurrently:
        // 1. Delete solve-1 via single solve deletion
        // 2. Select solve-4
        // 3. Delete solve-2 via single solve deletion
        val s1Domain = solves[1].toSolveTime()
        val s2Domain = solves[2].toSolveTime()
        val jobDelete1 = launch { viewModel.deleteSolve(s1Domain) }
        val jobSelect = launch { viewModel.toggleSolveSelection(solves[4].id) }
        val jobDelete2 = launch { viewModel.deleteSolve(s2Domain) }

        listOf(jobDelete1, jobSelect, jobDelete2).forEach { it.join() }
        advanceUntilIdle()

        // Solves 1 and 2 are deleted.
        // getSelectedSolves() must return only valid active solves 0, 3, 4!
        val activeSelected = viewModel.getSelectedSolves()
        val activeIds = activeSelected.map { it.id }.toSet()
        assertEquals(setOf(solves[0].id, solves[3].id, solves[4].id), activeIds)

        // Batch delete the remaining selected solves
        viewModel.deleteSelectedSolves()
        advanceUntilIdle()

        // Selection mode must be exited and selectedSolveIds must be empty
        assertFalse(viewModel.uiState.value.isSelectionMode)
        assertTrue(viewModel.uiState.value.selectedSolveIds.isEmpty())

        // In DB, solves 0, 1, 2, 3, 4 are now deleted; only 5, 6, 7 remain
        val remainingInDb = database.solveDao().getSolvesBySession("guest", session.id)
        assertEquals(3, remainingInDb.size)
        assertEquals(setOf(solves[5].id, solves[6].id, solves[7].id), remainingInDb.map { it.id }.toSet())
    }

    // ---------------------------------------------------------------------------------------------
    // Challenge Vector 4: Interleaved Batch Delete, Single Delete, Session Delete, and Multi-Undo
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testInterleavedBatchDeleteSingleDeleteSessionDeleteAndMultiUndo() = runTest(testDispatcher) {
        val (s1, s1Solves) = createSessionWithSolves("Session-Alpha", durationsMs = listOf(10000L, 11000L, 12000L, 13000L))
        val (s2, s2Solves) = createSessionWithSolves("Session-Beta", durationsMs = listOf(20000L, 21000L, 22000L, 23000L))
        val (s3, _) = createSessionWithSolves("Session-Gamma", durationsMs = listOf(30000L, 31000L, 32000L, 33000L))

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.expandSession(s1.id)
        viewModel.expandSession(s2.id)
        viewModel.expandSession(s3.id)
        advanceUntilIdle()

        assertEquals(3, viewModel.uiState.value.sessionGroups.size)

        // Action 1: Batch delete 2 solves from S1 (s1Solves[0], s1Solves[1])
        viewModel.startSelection(s1Solves[0].id)
        viewModel.toggleSolveSelection(s1Solves[1].id)
        viewModel.deleteSelectedSolves()

        // Action 2: Single delete 1 solve from S2 (s2Solves[0])
        viewModel.deleteSolve(s2Solves[0].toSolveTime())

        // Action 3: Delete session S3 entirely
        viewModel.deleteSession(s3)

        advanceUntilIdle()

        // Verify intermediate mutated state
        val intermediateState = viewModel.uiState.value
        assertEquals(2, intermediateState.sessionGroups.size)
        val s1Group = intermediateState.sessionGroups.first { it.id == s1.id }
        val s2Group = intermediateState.sessionGroups.first { it.id == s2.id }
        assertEquals(2, s1Group.solves.size)
        assertEquals(3, s2Group.solves.size)

        // Solves in DB check
        val activeSolvesS1 = database.solveDao().getSolvesBySession("guest", s1.id)
        val activeSolvesS2 = database.solveDao().getSolvesBySession("guest", s2.id)
        val activeSolvesS3 = database.solveDao().getSolvesBySession("guest", s3.id)
        assertEquals(2, activeSolvesS1.size)
        assertEquals(3, activeSolvesS2.size)
        assertEquals(0, activeSolvesS3.size)

        // Action 4: Multi-undo in interleaved sequence
        viewModel.undoDeleteBatch()
        viewModel.undoDelete()
        viewModel.undoDeleteSession()

        advanceUntilIdle()

        // Verify full restoration across all 3 actions
        val restoredSolvesS1 = database.solveDao().getSolvesBySession("guest", s1.id)
        val restoredSolvesS2 = database.solveDao().getSolvesBySession("guest", s2.id)
        val restoredSolvesS3 = database.solveDao().getSolvesBySession("guest", s3.id)

        assertEquals(4, restoredSolvesS1.size)
        assertEquals(4, restoredSolvesS2.size)
        assertEquals(4, restoredSolvesS3.size)

        val restoredState = viewModel.uiState.value
        assertEquals(3, restoredState.sessionGroups.size)
        val restoredGroupIds = restoredState.sessionGroups.map { it.id }.toSet()
        assertTrue(restoredGroupIds.contains(s1.id))
        assertTrue(restoredGroupIds.contains(s2.id))
        assertTrue(restoredGroupIds.contains(s3.id))
    }

    // ---------------------------------------------------------------------------------------------
    // Challenge Vector 5: Select All Under Active Filters (Invisible Solves Protection)
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testSelectAllUnderActiveFiltersDoesNotDeleteInvisibleSolves() = runTest(testDispatcher) {
        // S1 (expanded):
        // solve-0: 10.00s, Clean
        // solve-1: 12.00s, +2
        // solve-2: 15.00s, DNF
        // solve-3: 25.00s, Clean
        // solve-4: 30.00s, DNF
        // solve-5: 45.00s, Clean
        val (s1, s1Solves) = createSessionWithSolves(
            name = "Session-Filtered",
            durationsMs = listOf(10000L, 12000L, 15000L, 25000L, 30000L, 45000L),
            penalties = listOf("none", "+2", "dnf", "none", "dnf", "none")
        )

        // S2 (collapsed):
        // 3 clean solves
        val (s2, s2Solves) = createSessionWithSolves(
            name = "Session-Collapsed",
            durationsMs = listOf(11000L, 13000L, 17000L)
        )

        viewModel = createViewModel()
        advanceUntilIdle()

        // Expand only S1
        viewModel.expandSession(s1.id)
        advanceUntilIdle()

        // Step 1: Filter to DNF_ONLY
        viewModel.setPenaltyFilter(PenaltyFilter.DNF_ONLY)
        advanceUntilIdle()

        // S1 expanded visible solves must ONLY contain solve-2 and solve-4
        val s1FilteredGroup = viewModel.uiState.value.sessionGroups.first { it.id == s1.id }
        assertEquals(2, s1FilteredGroup.solves.size)
        val dnfIds = setOf(s1Solves[2].id, s1Solves[4].id)
        assertEquals(dnfIds, s1FilteredGroup.solves.map { it.id }.toSet())

        // Call Select All
        viewModel.selectAllSolves()
        advanceUntilIdle()

        // CRITICAL ASSERTION: Selected solves must strictly be the 2 visible DNF solves!
        // None of the clean/+2 solves in S1, nor any solves in collapsed S2, may be selected!
        assertEquals(dnfIds, viewModel.uiState.value.selectedSolveIds)
        assertEquals(2, viewModel.getSelectedSolves().size)

        // Delete Selected Solves
        viewModel.deleteSelectedSolves()
        advanceUntilIdle()

        // Verify in Room DB: ONLY the 2 DNF solves are soft-deleted. All other 7 solves remain active!
        val remainingS1 = database.solveDao().getSolvesBySession("guest", s1.id)
        assertEquals(4, remainingS1.size)
        val expectedActiveS1Ids = setOf(s1Solves[0].id, s1Solves[1].id, s1Solves[3].id, s1Solves[5].id)
        assertEquals(expectedActiveS1Ids, remainingS1.map { it.id }.toSet())

        val remainingS2 = database.solveDao().getSolvesBySession("guest", s2.id)
        assertEquals(3, remainingS2.size)

        // Step 2: Clear penalty filter, apply TimeRangeFilter (20.00s to 35.00s)
        viewModel.setPenaltyFilter(PenaltyFilter.ALL)
        viewModel.setTimeRangeFilter(20000L, 35000L)
        advanceUntilIdle()

        // In S1, only solve-3 (25.00s) matches 20s..35s!
        val s1TimeFiltered = viewModel.uiState.value.sessionGroups.first { it.id == s1.id }
        assertEquals(1, s1TimeFiltered.solves.size)
        assertEquals(s1Solves[3].id, s1TimeFiltered.solves.first().id)

        // Select All under TimeRangeFilter
        viewModel.selectAllSolves()
        advanceUntilIdle()

        assertEquals(setOf(s1Solves[3].id), viewModel.uiState.value.selectedSolveIds)

        // Delete Selected
        viewModel.deleteSelectedSolves()
        advanceUntilIdle()

        // In DB: solve-3 is deleted; solves 0, 1, 5 in S1 and all 3 solves in S2 remain intact!
        val finalS1 = database.solveDao().getSolvesBySession("guest", s1.id)
        assertEquals(3, finalS1.size)
        assertEquals(setOf(s1Solves[0].id, s1Solves[1].id, s1Solves[5].id), finalS1.map { it.id }.toSet())

        val finalS2 = database.solveDao().getSolvesBySession("guest", s2.id)
        assertEquals(3, finalS2.size)
    }

    // ---------------------------------------------------------------------------------------------
    // Challenge Vector 6: Select All Toggle and Deselection Cycle
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testSelectAllToggleDeselectionAndEmptyScope() = runTest(testDispatcher) {
        val (session, solves) = createSessionWithSolves("Toggle-Session", durationsMs = listOf(10000L, 11000L, 12000L))

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.expandSession(session.id)
        advanceUntilIdle()

        // First call: Selects all 3 visible solves
        viewModel.selectAllSolves()
        advanceUntilIdle()
        assertEquals(3, viewModel.uiState.value.selectedSolveIds.size)
        assertTrue(viewModel.uiState.value.isSelectionMode)

        // Second call: Toggles/deselects all visible solves
        viewModel.selectAllSolves()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.selectedSolveIds.isEmpty())
        assertFalse(viewModel.uiState.value.isSelectionMode)

        // Clear all solves and test selectAll with no solves in scope
        viewModel.deleteAllSolves()
        advanceUntilIdle()
        viewModel.clearSelection()
        advanceUntilIdle()

        // Select all when nothing exists in scope
        viewModel.selectAllSolves()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isSelectionMode)
        assertTrue(viewModel.uiState.value.selectedSolveIds.isEmpty())
    }

    // ---------------------------------------------------------------------------------------------
    // Challenge Vector 7: Rapid Filter and Sort Barrage Concurrency
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testRapidFilterAndSortBarrageConcurrency() = runTest(testDispatcher) {
        for (i in 0 until 4) {
            createSessionWithSolves(
                name = "Barrage-Session-$i",
                durationsMs = listOf(10000L + i * 200, 12000L + i * 200, 14000L + i * 200),
                penalties = listOf("none", "+2", "dnf")
            )
        }

        viewModel = createViewModel()
        advanceUntilIdle()

        // Rapid mixed mutations
        launch {
            repeat(15) {
                viewModel.setSessionSort(SessionSortOrder.NAME_ASC)
                viewModel.setSolveSort(SolveSortOrder.FASTEST)
                viewModel.setPenaltyFilter(PenaltyFilter.CLEAN)
                viewModel.setTimeRangeFilter(8000L, 15000L)
                viewModel.setDateRangeFilter(DatePreset.TODAY)

                viewModel.setSessionSort(SessionSortOrder.MOST_SOLVES)
                viewModel.setSolveSort(SolveSortOrder.SLOWEST)
                viewModel.setPenaltyFilter(PenaltyFilter.PLUS_TWO)
                viewModel.setTimeRangeFilter(null, null)

                viewModel.resetSolveFilters()
                viewModel.resetSessionFilters()
            }
            viewModel.resetAllFilters()
        }

        advanceUntilIdle()

        val finalState = viewModel.uiState.value
        assertEquals(0, finalState.totalActiveFilterCount)
        assertEquals(0, finalState.activeSessionFilterCount)
        assertEquals(0, finalState.activeSolveFilterCount)
        assertEquals(SessionSortOrder.MOST_RECENT, finalState.sessionSort)
        assertEquals(SolveSortOrder.MOST_RECENT, finalState.solveSort)
        assertEquals(PenaltyFilter.ALL, finalState.penaltyFilter)
        assertFalse(finalState.timeRangeFilter.isActive)
        assertFalse(finalState.dateRangeFilter.isActive)
        assertEquals(4, finalState.sessionGroups.size)
    }

    // ---------------------------------------------------------------------------------------------
    // Challenge Vector 8: Authentication Switch Flushes Cache and Cancels Ongoing Jobs
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testAuthenticationSwitchCancelsChildJobsAndIsolatesCache() = runTest(testDispatcher) {
        val (guestSession, guestSolves) = createSessionWithSolves("Guest-Session", ownerId = "guest")

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.expandSession(guestSession.id)
        advanceUntilIdle()

        // Guest session is expanded and has solves cached
        val initialGroups = viewModel.uiState.value.sessionGroups
        assertEquals(1, initialGroups.size)
        assertEquals(guestSession.id, initialGroups[0].id)
        assertEquals(3, initialGroups[0].solves.size)

        val jobsBeforeAuth = getExpandedJobs(viewModel)
        assertEquals(1, jobsBeforeAuth.size)
        assertTrue(jobsBeforeAuth[guestSession.id]?.isActive == true)

        // Switch to authenticated user "user-adversary"
        fakeAuthManager.setAuthState(AuthState.Authenticated(User(id = "user-adversary", email = "adv@test.com")))
        advanceUntilIdle()

        // UI state displays user-adversary sessions (which are currently empty)
        val userGroups = viewModel.uiState.value.sessionGroups
        assertTrue(userGroups.isEmpty())

        // Verify: Guest solve cache is cleared and no solves leak to user-adversary
        val solvesInCache = viewModel.uiState.value.sessionGroups.flatMap { it.solves }
        assertTrue(solvesInCache.isEmpty())

        // Collapsing the session cleanly removes and cancels any background job
        viewModel.collapseSession(guestSession.id)
        advanceUntilIdle()
        val jobsAfterCollapse = getExpandedJobs(viewModel)
        assertFalse(jobsAfterCollapse.containsKey(guestSession.id))
    }

    // ---------------------------------------------------------------------------------------------
    // Challenge Vector 9: Undo Idempotency and Double-Undo Stress
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testUndoIdempotencyAndDoubleUndoStress() = runTest(testDispatcher) {
        val (s1, solves) = createSessionWithSolves("Idempotency-Session")

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.expandSession(s1.id)
        advanceUntilIdle()

        // 1. Batch delete & double undo
        viewModel.startSelection(solves[0].id)
        viewModel.deleteSelectedSolves()
        advanceUntilIdle()
        assertEquals(2, database.solveDao().getSolvesBySession("guest", s1.id).size)

        viewModel.undoDeleteBatch()
        advanceUntilIdle()
        assertEquals(3, database.solveDao().getSolvesBySession("guest", s1.id).size)

        // Second undo call must be a safe no-op (no double restore, no SQLite duplicate primary key crash)
        viewModel.undoDeleteBatch()
        advanceUntilIdle()
        assertEquals(3, database.solveDao().getSolvesBySession("guest", s1.id).size)

        // 2. Session delete & double undo
        viewModel.deleteSession(s1)
        advanceUntilIdle()
        assertEquals(0, database.sessionDao().getAllActiveSessionsForOwner("guest").size)

        viewModel.undoDeleteSession()
        advanceUntilIdle()
        assertEquals(1, database.sessionDao().getAllActiveSessionsForOwner("guest").size)

        // Second undo call must be a safe no-op
        viewModel.undoDeleteSession()
        advanceUntilIdle()
        assertEquals(1, database.sessionDao().getAllActiveSessionsForOwner("guest").size)

        // 3. Delete all & double undo
        viewModel.deleteAllSolves()
        advanceUntilIdle()
        assertEquals(0, database.solveDao().getSolvesBySession("guest", s1.id).size)

        viewModel.undoDeleteAllSolves()
        advanceUntilIdle()
        assertEquals(3, database.solveDao().getSolvesBySession("guest", s1.id).size)

        // Second undo call must be a safe no-op
        viewModel.undoDeleteAllSolves()
        advanceUntilIdle()
        assertEquals(3, database.solveDao().getSolvesBySession("guest", s1.id).size)
    }

    // ---------------------------------------------------------------------------------------------
    // Challenge Vector 10: SAF CSV Export Edge Cases Under Stress
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testExportSafEdgeCasesUnderStress() = runTest(testDispatcher) {
        val emptySession = sessionRepository.createManualSession("Empty-Session", Mode.CUBE_3x3, "guest")

        viewModel = createViewModel()
        advanceUntilIdle()

        val out1 = ByteArrayOutputStream()
        val count1 = viewModel.exportSelectedSolvesToStream(out1)
        assertEquals(0, count1)
        assertEquals(0, out1.size())

        val out2 = ByteArrayOutputStream()
        val count2 = viewModel.exportSessionToStream(emptySession, out2)
        assertEquals(0, count2)
        assertEquals(0, out2.size())

        val out3 = ByteArrayOutputStream()
        val count3 = viewModel.exportAllSolvesToStream(out3)
        assertEquals(0, count3)
        assertEquals(0, out3.size())
    }

    // ---------------------------------------------------------------------------------------------
    // Fake Auth Manager
    // ---------------------------------------------------------------------------------------------
    private class FakeAuthManager : AuthManager {
        private val _authState = MutableStateFlow<AuthState>(AuthState.Guest)
        override val authState: StateFlow<AuthState> = _authState.asStateFlow()
        override var currentUser: User? = null

        fun setAuthState(state: AuthState) {
            _authState.value = state
            currentUser = when (state) {
                is AuthState.Authenticated -> state.user
                is AuthState.Admin -> state.user
                else -> null
            }
        }

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
