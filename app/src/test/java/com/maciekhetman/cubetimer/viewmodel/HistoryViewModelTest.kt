package com.maciekhetman.cubetimer.viewmodel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.maciekhetman.cubetimer.data.SolvesRepository
import com.maciekhetman.cubetimer.data.auth.AuthManager
import com.maciekhetman.cubetimer.data.auth.AuthResult
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import com.maciekhetman.cubetimer.data.session.SessionManagerImpl
import com.maciekhetman.cubetimer.data.session.SessionRepositoryImpl
import com.maciekhetman.cubetimer.data.local.converter.CubeTypeConverters
import com.maciekhetman.cubetimer.domain.csv.CsvImportStatus
import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Penalty
import com.maciekhetman.cubetimer.model.Session
import com.maciekhetman.cubetimer.model.SessionKind
import com.maciekhetman.cubetimer.model.SolveTime
import com.maciekhetman.cubetimer.model.StatsFilter
import com.maciekhetman.cubetimer.model.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HistoryViewModelTest {

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

    @Test
    fun testInitialLoadWithChunkedPagination() = runTest(testDispatcher) {
        val baseTime = Instant.parse("2026-08-30T10:00:00.000Z")
        val entities = (0 until 65).map { i ->
            SolveEntity(
                id = "solve-$i",
                ownerId = "guest",
                event = "3x3",
                durationMs = 10000L + i * 10,
                penalty = "none",
                solvedAt = baseTime.plus(i.toLong(), ChronoUnit.MINUTES).toString(),
                scramble = "R U R' U'",
                version = 0L
            )
        }
        database.solveDao().insertAll(entities)

        viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(50, state.solves.size)
        assertTrue(state.hasMore)
        assertEquals(65, state.totalCount)
        assertEquals("solve-64", state.solves.first().id)
        assertEquals("solve-15", state.solves.last().id)
        assertFalse(state.isLoading)
    }

    @Test
    fun testLoadMoreAppendsWithoutDuplication() = runTest(testDispatcher) {
        val baseTime = Instant.parse("2026-08-30T10:00:00.000Z")
        val entities = (0 until 65).map { i ->
            SolveEntity(
                id = "solve-$i",
                ownerId = "guest",
                event = "3x3",
                durationMs = 10000L + i * 10,
                penalty = "none",
                solvedAt = baseTime.plus(i.toLong(), ChronoUnit.MINUTES).toString(),
                scramble = "R U R' U'",
                version = 0L
            )
        }
        database.solveDao().insertAll(entities)

        viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(50, viewModel.uiState.value.solves.size)
        assertTrue(viewModel.uiState.value.hasMore)

        viewModel.loadMore()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(65, state.solves.size)
        assertFalse(state.hasMore)
        assertEquals("solve-0", state.solves.last().id)
    }

    @Test
    fun testSetFilterChangesScopeAndReloads() = runTest(testDispatcher) {
        val session1 = sessionRepository.createManualSession("S1", Mode.CUBE_3x3, "guest")
        val session2 = sessionRepository.createManualSession("S2", Mode.CUBE_3x3, "guest")

        database.solveDao().insert(
            SolveEntity(
                id = "s1-solve",
                ownerId = "guest",
                sessionId = session1.id,
                event = "3x3",
                durationMs = 12000L,
                penalty = "none",
                solvedAt = "2026-08-30T10:00:00.000Z",
                scramble = "R",
                version = 0L
            )
        )
        database.solveDao().insert(
            SolveEntity(
                id = "s2-solve",
                ownerId = "guest",
                sessionId = session2.id,
                event = "3x3",
                durationMs = 14000L,
                penalty = "none",
                solvedAt = "2026-08-30T10:01:00.000Z",
                scramble = "U",
                version = 0L
            )
        )

        viewModel = createViewModel()
        advanceUntilIdle()

        // Filter by specific session 2
        viewModel.setFilter(StatsFilter.SpecificSession(session2.id, session2.name))
        advanceUntilIdle()

        val s2State = viewModel.uiState.value
        assertEquals(1, s2State.solves.size)
        assertEquals("s2-solve", s2State.solves.first().id)

        // Filter by All Sessions
        viewModel.setFilter(StatsFilter.AllSessions)
        advanceUntilIdle()

        val allState = viewModel.uiState.value
        assertEquals(2, allState.solves.size)
    }

    @Test
    fun testOptimisticPenaltyUpdate() = runTest(testDispatcher) {
        val solveEntity = SolveEntity(
            id = "solve-pen",
            ownerId = "guest",
            event = "3x3",
            durationMs = 12000L,
            penalty = "none",
            solvedAt = "2026-08-30T10:00:00.000Z",
            scramble = "R U",
            version = 0L
        )
        database.solveDao().insert(solveEntity)

        viewModel = createViewModel()
        advanceUntilIdle()

        val solveTime = viewModel.uiState.value.solves.first()
        viewModel.updateSolvePenalty(solveTime, Penalty.PLUS_TWO)
        advanceUntilIdle()

        val updatedInState = viewModel.uiState.value.solves.first()
        assertEquals(Penalty.PLUS_TWO, updatedInState.penalty)

        val inDb = database.solveDao().getSolveById("solve-pen")
        assertEquals("plus_two", inDb?.penalty)
    }

    @Test
    fun testDeleteSolveAndUndo() = runTest(testDispatcher) {
        val solveEntity = SolveEntity(
            id = "solve-del",
            ownerId = "guest",
            event = "3x3",
            durationMs = 12000L,
            penalty = "none",
            solvedAt = "2026-08-30T10:00:00.000Z",
            scramble = "R U",
            version = 0L
        )
        database.solveDao().insert(solveEntity)

        viewModel = createViewModel()
        advanceUntilIdle()

        val solveToDelete = viewModel.uiState.value.solves.first()
        viewModel.deleteSolve(solveToDelete)
        advanceUntilIdle()

        // State immediately reflects deletion
        assertEquals(0, viewModel.uiState.value.solves.size)
        assertEquals(0, viewModel.uiState.value.totalCount)
        val inDbSoftDeleted = database.solveDao().getSolveById("solve-del")
        assertNotNull(inDbSoftDeleted?.deletedAt)

        // Undo delete
        viewModel.undoDelete()
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.solves.size)
        assertEquals("solve-del", viewModel.uiState.value.solves.first().id)
        val inDbRestored = database.solveDao().getSolveById("solve-del")
        assertNull(inDbRestored?.deletedAt)
    }

    @Test
    fun testClearHistoryAndUndo() = runTest(testDispatcher) {
        database.solveDao().insert(
            SolveEntity(
                id = "s1",
                ownerId = "guest",
                event = "3x3",
                durationMs = 10000L,
                penalty = "none",
                solvedAt = "2026-08-30T10:00:00.000Z",
                scramble = "R",
                version = 0L
            )
        )
        database.solveDao().insert(
            SolveEntity(
                id = "s2",
                ownerId = "guest",
                event = "3x3",
                durationMs = 11000L,
                penalty = "none",
                solvedAt = "2026-08-30T10:01:00.000Z",
                scramble = "U",
                version = 0L
            )
        )

        viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.solves.size)

        viewModel.clearHistory()
        advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.solves.size)
        assertEquals(0, viewModel.uiState.value.totalCount)

        viewModel.undoClearHistory()
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.solves.size)
    }

    @Test
    fun testSelectSolveForDetailCalculatesPbMetrics() = runTest(testDispatcher) {
        // Solve 1: 15.00s at 10:00
        database.solveDao().insert(
            SolveEntity(
                id = "s1",
                ownerId = "guest",
                event = "3x3",
                durationMs = 15000L,
                penalty = "none",
                solvedAt = "2026-08-30T10:00:00Z",
                scramble = "R",
                version = 0L
            )
        )
        // Solve 2: 12.00s at 10:01 (PB!)
        database.solveDao().insert(
            SolveEntity(
                id = "s2",
                ownerId = "guest",
                event = "3x3",
                durationMs = 12000L,
                penalty = "none",
                solvedAt = "2026-08-30T10:01:00Z",
                scramble = "R U",
                version = 0L
            )
        )
        // Solve 3: 13.00s at 10:02 (not PB vs 12.00s)
        database.solveDao().insert(
            SolveEntity(
                id = "s3",
                ownerId = "guest",
                event = "3x3",
                durationMs = 13000L,
                penalty = "none",
                solvedAt = "2026-08-30T10:02:00Z",
                scramble = "R U2",
                version = 0L
            )
        )

        viewModel = createViewModel()
        advanceUntilIdle()

        val solve2 = viewModel.uiState.value.solves.first { it.id == "s2" }
        viewModel.selectSolveForDetail(solve2)
        advanceUntilIdle()

        val detail2 = viewModel.selectedSolveDetail.value
        assertNotNull(detail2)
        assertEquals(15000L, detail2?.priorBestTime)
        assertTrue(detail2?.isPb == true)
        assertEquals(3000L, detail2?.pbDelta)
        assertEquals(2, detail2?.solveNumber)

        val solve3 = viewModel.uiState.value.solves.first { it.id == "s3" }
        viewModel.selectSolveForDetail(solve3)
        advanceUntilIdle()

        val detail3 = viewModel.selectedSolveDetail.value
        assertNotNull(detail3)
        assertEquals(12000L, detail3?.priorBestTime)
        assertFalse(detail3?.isPb == true)
        assertNull(detail3?.pbDelta)
        assertEquals(3, detail3?.solveNumber)

        viewModel.dismissSolveDetail()
        assertNull(viewModel.selectedSolveDetail.value)
    }

    private suspend fun createSessionWithSolves(
        name: String,
        mode: Mode = Mode.CUBE_3x3,
        ownerId: String = "guest",
        durationsMs: List<Long> = listOf(10000L, 12000L)
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
                solvedAt = Instant.parse("2026-08-30T10:00:00.000Z").plus(index.toLong(), ChronoUnit.MINUTES).toString(),
                scramble = "R U R' U'",
                version = 0L
            )
        }
        database.solveDao().insertAll(entities)
        return Pair(session, entities)
    }

    @Test
    fun testSessionGroupsInitialStateWithStats() = runTest(testDispatcher) {
        val (session1, _) = createSessionWithSolves("Session 1", durationsMs = listOf(10000L, 12000L))
        val (session2, _) = createSessionWithSolves("Session 2", durationsMs = listOf(8000L))

        viewModel = createViewModel()
        advanceUntilIdle()

        val groups = viewModel.uiState.value.sessionGroups
        assertEquals(2, groups.size)

        val group1 = groups.first { it.session.id == session1.id }
        assertEquals(2, group1.solveCount)
        assertEquals(10000L, group1.bestDurationMs)
        assertEquals(11000L, group1.avgDurationMs)
        assertFalse(group1.isExpanded)
        assertTrue(group1.solves.isEmpty())

        val group2 = groups.first { it.session.id == session2.id }
        assertEquals(1, group2.solveCount)
        assertEquals(8000L, group2.bestDurationMs)
        assertEquals(8000L, group2.avgDurationMs)
    }

    @Test
    fun testExpandAndCollapseSession() = runTest(testDispatcher) {
        val (session1, _) = createSessionWithSolves("Session 1", durationsMs = listOf(10000L, 12000L))

        viewModel = createViewModel()
        advanceUntilIdle()

        var group = viewModel.uiState.value.sessionGroups.first { it.session.id == session1.id }
        assertFalse(group.isExpanded)
        assertTrue(group.solves.isEmpty())

        // Expand
        viewModel.expandSession(session1.id)
        advanceUntilIdle()

        group = viewModel.uiState.value.sessionGroups.first { it.session.id == session1.id }
        assertTrue(group.isExpanded)
        assertEquals(2, group.solves.size)
        assertTrue(viewModel.uiState.value.expandedSessionIds.contains(session1.id))

        // Collapse
        viewModel.collapseSession(session1.id)
        advanceUntilIdle()

        group = viewModel.uiState.value.sessionGroups.first { it.session.id == session1.id }
        assertFalse(group.isExpanded)
        assertTrue(group.solves.isEmpty())
        assertFalse(viewModel.uiState.value.expandedSessionIds.contains(session1.id))
    }

    @Test
    fun testToggleSessionExpanded() = runTest(testDispatcher) {
        val (session1, _) = createSessionWithSolves("Session 1", durationsMs = listOf(9000L))

        viewModel = createViewModel()
        advanceUntilIdle()

        // Toggle to expand
        viewModel.toggleSessionExpanded(session1.id)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.expandedSessionIds.contains(session1.id))
        assertTrue(viewModel.uiState.value.sessionGroups.first { it.session.id == session1.id }.isExpanded)

        // Toggle to collapse
        viewModel.toggleSessionExpanded(session1.id)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.expandedSessionIds.contains(session1.id))
        assertFalse(viewModel.uiState.value.sessionGroups.first { it.session.id == session1.id }.isExpanded)
    }

    @Test
    fun testMultipleSessionsExpandedConcurrently() = runTest(testDispatcher) {
        val (session1, _) = createSessionWithSolves("Session 1", durationsMs = listOf(10000L))
        val (session2, _) = createSessionWithSolves("Session 2", durationsMs = listOf(12000L, 14000L))

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.expandSession(session1.id)
        viewModel.expandSession(session2.id)
        advanceUntilIdle()

        val g1 = viewModel.uiState.value.sessionGroups.first { it.session.id == session1.id }
        val g2 = viewModel.uiState.value.sessionGroups.first { it.session.id == session2.id }
        assertTrue(g1.isExpanded)
        assertEquals(1, g1.solves.size)
        assertTrue(g2.isExpanded)
        assertEquals(2, g2.solves.size)

        // Collapse session 1 only
        viewModel.collapseSession(session1.id)
        advanceUntilIdle()

        val g1After = viewModel.uiState.value.sessionGroups.first { it.session.id == session1.id }
        val g2After = viewModel.uiState.value.sessionGroups.first { it.session.id == session2.id }
        assertFalse(g1After.isExpanded)
        assertTrue(g2After.isExpanded)
        assertEquals(2, g2After.solves.size)
    }

    @Test
    fun testMultiSelectionFlow() = runTest(testDispatcher) {
        val (session1, solves) = createSessionWithSolves("Session 1", durationsMs = listOf(10000L, 12000L, 14000L))

        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.expandSession(session1.id)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isSelectionMode)
        assertTrue(viewModel.uiState.value.selectedSolveIds.isEmpty())

        // Start selection
        viewModel.startSelection(solves[0].id)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isSelectionMode)
        assertEquals(setOf(solves[0].id), viewModel.uiState.value.selectedSolveIds)

        // Toggle second solve
        viewModel.toggleSolveSelection(solves[1].id)
        advanceUntilIdle()

        assertEquals(setOf(solves[0].id, solves[1].id), viewModel.uiState.value.selectedSolveIds)

        // Toggle first solve off
        viewModel.toggleSolveSelection(solves[0].id)
        advanceUntilIdle()

        assertEquals(setOf(solves[1].id), viewModel.uiState.value.selectedSolveIds)

        // Clear selection
        viewModel.clearSelection()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isSelectionMode)
        assertTrue(viewModel.uiState.value.selectedSolveIds.isEmpty())
    }

    @Test
    fun testSelectAllSolvesAndGetSelectedSolves() = runTest(testDispatcher) {
        val (session1, solves) = createSessionWithSolves("Session 1", durationsMs = listOf(10000L, 12000L))

        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.expandSession(session1.id)
        advanceUntilIdle()

        viewModel.selectAllSolves()
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.selectedSolveIds.size)
        assertTrue(viewModel.uiState.value.selectedSolveIds.containsAll(listOf(solves[0].id, solves[1].id)))

        val selectedSolves = viewModel.getSelectedSolves()
        assertEquals(2, selectedSolves.size)
        assertEquals(setOf(solves[0].id, solves[1].id), selectedSolves.map { it.id }.toSet())

        // Calling selectAllSolves again deselects all
        viewModel.selectAllSolves()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.selectedSolveIds.isEmpty())
        assertTrue(viewModel.getSelectedSolves().isEmpty())
    }

    @Test
    fun testDeleteSelectedSolvesAndUndo() = runTest(testDispatcher) {
        val (session1, solves) = createSessionWithSolves("Session 1", durationsMs = listOf(10000L, 12000L, 14000L))

        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.expandSession(session1.id)
        advanceUntilIdle()

        viewModel.startSelection(solves[0].id)
        viewModel.toggleSolveSelection(solves[1].id)
        advanceUntilIdle()

        viewModel.uiEffect.test {
            viewModel.deleteSelectedSolves()
            advanceUntilIdle()

            val effect = awaitItem()
            assertTrue(effect is HistoryUiEffect.ShowUndoBatchDelete)
            val batchEffect = effect as HistoryUiEffect.ShowUndoBatchDelete
            assertEquals(2, batchEffect.deletedSolves.size)

            // Verify in DB soft deleted
            assertNotNull(database.solveDao().getSolveById(solves[0].id)?.deletedAt)
            assertNotNull(database.solveDao().getSolveById(solves[1].id)?.deletedAt)
            assertNull(database.solveDao().getSolveById(solves[2].id)?.deletedAt)

            // Undo
            viewModel.undoDeleteBatch(batchEffect.deletedSolves)
            advanceUntilIdle()

            val restoreEffect = awaitItem()
            assertTrue(restoreEffect is HistoryUiEffect.ShowMessage)

            // Verify restored in DB
            assertNull(database.solveDao().getSolveById(solves[0].id)?.deletedAt)
            assertNull(database.solveDao().getSolveById(solves[1].id)?.deletedAt)
        }
    }

    @Test
    fun testDeleteSessionAndUndo() = runTest(testDispatcher) {
        val (session1, solves) = createSessionWithSolves("Session 1", durationsMs = listOf(10000L, 12000L))

        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.expandSession(session1.id)
        advanceUntilIdle()

        viewModel.uiEffect.test {
            viewModel.deleteSession(session1)
            advanceUntilIdle()

            val effect = awaitItem()
            assertTrue(effect is HistoryUiEffect.ShowUndoSessionDelete)
            val sessionEffect = effect as HistoryUiEffect.ShowUndoSessionDelete
            assertEquals(session1.name, sessionEffect.sessionName)
            assertEquals(2, sessionEffect.solvesCount)

            // Verify session & solves soft deleted in DB
            assertNotNull(database.sessionDao().getSessionById(session1.id)?.deletedAt)
            assertNotNull(database.solveDao().getSolveById(solves[0].id)?.deletedAt)

            // Undo session deletion
            viewModel.undoDeleteSession()
            advanceUntilIdle()

            val restoreEffect = awaitItem()
            assertTrue(restoreEffect is HistoryUiEffect.ShowMessage)

            // Verify session & solves restored in DB
            assertNull(database.sessionDao().getSessionById(session1.id)?.deletedAt)
            assertNull(database.solveDao().getSolveById(solves[0].id)?.deletedAt)
        }
    }

    @Test
    fun testDeleteAllSolvesAndUndo() = runTest(testDispatcher) {
        val (session1, solves) = createSessionWithSolves("Session 1", durationsMs = listOf(10000L, 12000L))

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.uiEffect.test {
            viewModel.deleteAllSolves()
            advanceUntilIdle()

            val effect = awaitItem()
            assertTrue(effect is HistoryUiEffect.ShowUndoClearAll)
            val clearEffect = effect as HistoryUiEffect.ShowUndoClearAll
            assertEquals(2, clearEffect.deletedSolves.size)

            assertEquals(0, viewModel.uiState.value.totalCount)

            viewModel.undoDeleteAllSolves()
            advanceUntilIdle()

            val restoreEffect = awaitItem()
            assertTrue(restoreEffect is HistoryUiEffect.ShowMessage)

            assertNull(database.solveDao().getSolveById(solves[0].id)?.deletedAt)
            assertNull(database.solveDao().getSolveById(solves[1].id)?.deletedAt)
        }
    }

    @Test
    fun testSessionSorting() = runTest(testDispatcher) {
        // Create 3 sessions with distinct names, dates, and solve counts
        val (sAlpha, _) = createSessionWithSolves("Alpha", durationsMs = listOf(10000L))
        val (sZeta, _) = createSessionWithSolves("Zeta", durationsMs = listOf(11000L, 12000L, 13000L))
        val (sBeta, _) = createSessionWithSolves("Beta", durationsMs = listOf(14000L, 15000L))

        viewModel = createViewModel()
        advanceUntilIdle()

        // Name Ascending
        viewModel.setSessionSort(SessionSortOrder.NAME_ASC)
        advanceUntilIdle()
        var names = viewModel.uiState.value.sessionGroups.map { it.session.name }
        assertEquals(listOf("Alpha", "Beta", "Zeta"), names)

        // Name Descending
        viewModel.setSessionSort(SessionSortOrder.NAME_DESC)
        advanceUntilIdle()
        names = viewModel.uiState.value.sessionGroups.map { it.session.name }
        assertEquals(listOf("Zeta", "Beta", "Alpha"), names)

        // Most Solves
        viewModel.setSessionSort(SessionSortOrder.MOST_SOLVES)
        advanceUntilIdle()
        val counts = viewModel.uiState.value.sessionGroups.map { it.solveCount }
        assertEquals(listOf(3, 2, 1), counts)
    }

    @Test
    fun testSessionPuzzleScopeAndKindFilter() = runTest(testDispatcher) {
        // Create 3x3 session and 2x2 session
        val (s3x3, _) = createSessionWithSolves("Session 3x3", mode = Mode.CUBE_3x3, durationsMs = listOf(10000L))
        val (s2x2, _) = createSessionWithSolves("Session 2x2", mode = Mode.CUBE_2x2, durationsMs = listOf(5000L))

        viewModel = createViewModel()
        advanceUntilIdle()

        // Default: ACTIVE_PUZZLE (3x3), so only 3x3 session should be present
        assertEquals(1, viewModel.uiState.value.sessionGroups.size)
        assertEquals(s3x3.id, viewModel.uiState.value.sessionGroups.first().session.id)

        // Switch to ALL_PUZZLES: both sessions present
        viewModel.setPuzzleScope(PuzzleScope.ALL_PUZZLES)
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.sessionGroups.size)

        // Switch kind filter to MANUAL_ONLY (both are manual)
        viewModel.setSessionKindFilter(SessionKindFilter.MANUAL_ONLY)
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.sessionGroups.size)

        // Switch kind filter to AUTOMATIC_ONLY: none are automatic
        viewModel.setSessionKindFilter(SessionKindFilter.AUTOMATIC_ONLY)
        advanceUntilIdle()
        assertEquals(0, viewModel.uiState.value.sessionGroups.size)
    }

    @Test
    fun testSolveTabSortingAndFiltering() = runTest(testDispatcher) {
        val session = sessionRepository.createManualSession("S1", Mode.CUBE_3x3, "guest")
        val baseTime = Instant.parse("2026-08-30T10:00:00.000Z")
        val solveFast = SolveEntity(
            id = "solve-fast",
            ownerId = "guest",
            sessionId = session.id,
            event = "3x3",
            durationMs = 9000L,
            penalty = "none",
            solvedAt = baseTime.toString(),
            scramble = "R",
            version = 0L
        )
        val solveSlow = SolveEntity(
            id = "solve-slow",
            ownerId = "guest",
            sessionId = session.id,
            event = "3x3",
            durationMs = 25000L,
            penalty = "none",
            solvedAt = baseTime.plus(1, ChronoUnit.MINUTES).toString(),
            scramble = "U",
            version = 0L
        )
        val solvePlusTwo = SolveEntity(
            id = "solve-p2",
            ownerId = "guest",
            sessionId = session.id,
            event = "3x3",
            durationMs = 12000L,
            penalty = "plus_two",
            solvedAt = baseTime.plus(2, ChronoUnit.MINUTES).toString(),
            scramble = "F",
            version = 0L
        )
        val solveDnf = SolveEntity(
            id = "solve-dnf",
            ownerId = "guest",
            sessionId = session.id,
            event = "3x3",
            durationMs = 11000L,
            penalty = "dnf",
            solvedAt = baseTime.plus(3, ChronoUnit.MINUTES).toString(),
            scramble = "B",
            version = 0L
        )
        database.solveDao().insertAll(listOf(solveFast, solveSlow, solvePlusTwo, solveDnf))

        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.expandSession(session.id)
        advanceUntilIdle()

        // 1. Sort FASTEST
        viewModel.setSolveSort(SolveSortOrder.FASTEST)
        advanceUntilIdle()
        var groupSolves = viewModel.uiState.value.sessionGroups.first().solves
        assertEquals(4, groupSolves.size)
        assertEquals("solve-fast", groupSolves.first().id)

        // 2. Sort SLOWEST
        viewModel.setSolveSort(SolveSortOrder.SLOWEST)
        advanceUntilIdle()
        groupSolves = viewModel.uiState.value.sessionGroups.first().solves
        assertEquals("solve-slow", groupSolves.first().id)

        // 3. PenaltyFilter.PLUS_TWO
        viewModel.setPenaltyFilter(PenaltyFilter.PLUS_TWO)
        advanceUntilIdle()
        groupSolves = viewModel.uiState.value.sessionGroups.first().solves
        assertEquals(1, groupSolves.size)
        assertEquals("solve-p2", groupSolves.first().id)

        // 4. PenaltyFilter.DNF
        viewModel.setPenaltyFilter(PenaltyFilter.DNF)
        advanceUntilIdle()
        groupSolves = viewModel.uiState.value.sessionGroups.first().solves
        assertEquals(1, groupSolves.size)
        assertEquals("solve-dnf", groupSolves.first().id)

        // Reset penalty filter to ALL
        viewModel.setPenaltyFilter(PenaltyFilter.ALL)
        advanceUntilIdle()

        // 5. TimeRangeFilter (between 8s and 10s -> only solve-fast)
        viewModel.setTimeRangeFilter(8000L, 10000L)
        advanceUntilIdle()
        groupSolves = viewModel.uiState.value.sessionGroups.first().solves
        assertEquals(1, groupSolves.size)
        assertEquals("solve-fast", groupSolves.first().id)
    }

    @Test
    fun testActiveFilterCountBadgesAndReset() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.activeSessionFilterCount)
        assertEquals(0, viewModel.uiState.value.activeSolveFilterCount)
        assertEquals(0, viewModel.uiState.value.totalActiveFilterCount)

        // Change session filters
        viewModel.setSessionSort(SessionSortOrder.NAME_ASC) // +1
        viewModel.setPuzzleScope(PuzzleScope.ALL_PUZZLES)   // +1
        viewModel.setSessionKindFilter(SessionKindFilter.MANUAL_ONLY) // +1
        advanceUntilIdle()

        assertEquals(3, viewModel.uiState.value.activeSessionFilterCount)
        assertEquals(0, viewModel.uiState.value.activeSolveFilterCount)
        assertEquals(3, viewModel.uiState.value.totalActiveFilterCount)

        // Change solve filters
        viewModel.setSolveSort(SolveSortOrder.FASTEST) // +1
        viewModel.setPenaltyFilter(PenaltyFilter.PLUS_TWO) // +1
        viewModel.setTimeRangeFilter(10000L, 20000L) // +1
        advanceUntilIdle()

        assertEquals(3, viewModel.uiState.value.activeSessionFilterCount)
        assertEquals(3, viewModel.uiState.value.activeSolveFilterCount)
        assertEquals(6, viewModel.uiState.value.totalActiveFilterCount)

        // Reset solve filters
        viewModel.resetSolveFilters()
        advanceUntilIdle()

        assertEquals(3, viewModel.uiState.value.activeSessionFilterCount)
        assertEquals(0, viewModel.uiState.value.activeSolveFilterCount)
        assertEquals(3, viewModel.uiState.value.totalActiveFilterCount)

        // Reset all filters
        viewModel.resetAllFilters()
        advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.activeSessionFilterCount)
        assertEquals(0, viewModel.uiState.value.activeSolveFilterCount)
        assertEquals(0, viewModel.uiState.value.totalActiveFilterCount)
        assertEquals(SessionSortOrder.MOST_RECENT, viewModel.uiState.value.sessionSort)
        assertEquals(PuzzleScope.ACTIVE_PUZZLE, viewModel.uiState.value.puzzleScope)
    }

    @Test
    fun testFilterSheetOpenCloseAndTab() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isFilterSheetOpen)
        assertEquals(0, viewModel.uiState.value.activeFilterSheetTab)

        viewModel.openFilterSheet(initialTab = 1)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isFilterSheetOpen)
        assertEquals(1, viewModel.uiState.value.activeFilterSheetTab)

        viewModel.setActiveFilterSheetTab(0)
        advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.activeFilterSheetTab)

        viewModel.closeFilterSheet()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isFilterSheetOpen)
    }

    @Test
    fun testExportAllSolvesToStream() = runTest(testDispatcher) {
        val (session, _) = createSessionWithSolves("Session 1", durationsMs = listOf(10000L, 12000L))

        viewModel = createViewModel()
        advanceUntilIdle()

        val outputStream = java.io.ByteArrayOutputStream()
        val count = viewModel.exportAllSolvesToStream(outputStream)

        assertEquals(2, count)
        val csvText = outputStream.toString("UTF-8")
        assertTrue(csvText.contains("# Source: CubeTimer"))
        assertTrue(csvText.contains("solve_id,session_id,session_name,puzzle,timestamp,time,penalty,scramble"))
        assertTrue(csvText.contains("Session 1"))
        assertTrue(csvText.contains("10000"))
    }

    @Test
    fun testExportSessionToStream() = runTest(testDispatcher) {
        val (session1, _) = createSessionWithSolves("S1", durationsMs = listOf(10000L))
        val (session2, _) = createSessionWithSolves("S2", durationsMs = listOf(20000L))

        viewModel = createViewModel()
        advanceUntilIdle()

        val outputStream = java.io.ByteArrayOutputStream()
        val count = viewModel.exportSessionToStream(session1, outputStream)

        assertEquals(1, count)
        val csvText = outputStream.toString("UTF-8")
        assertTrue(csvText.contains("S1"))
        assertTrue(csvText.contains("10000"))
        assertFalse(csvText.contains("20000"))
    }

    @Test
    fun testExportSelectedSolvesToStream() = runTest(testDispatcher) {
        val (session, solves) = createSessionWithSolves("S1", durationsMs = listOf(10000L, 12000L))

        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.expandSession(session.id)
        advanceUntilIdle()

        // Select only the first solve
        viewModel.startSelection(solves[0].id)
        advanceUntilIdle()

        val outputStream = java.io.ByteArrayOutputStream()
        val count = viewModel.exportSelectedSolvesToStream(outputStream)

        assertEquals(1, count)
        val csvText = outputStream.toString("UTF-8")
        assertTrue(csvText.contains(solves[0].id))
        assertFalse(csvText.contains(solves[1].id))
    }

    @Test
    fun testImportSolvesFromStreamSuccessAndInvalid() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()

        // 1. Import empty stream
        val emptyStream = java.io.ByteArrayInputStream(ByteArray(0))
        val emptyResult = viewModel.importSolvesFromStream(emptyStream)
        assertTrue(emptyResult is CsvImportStatus.EmptyFile)

        // 2. Import valid CSV stream
        val validCsv = """
            # Source: CubeTimer
            solve_id,session_id,session_name,puzzle,timestamp,time,penalty,scramble
            csv-solve-1,,ImportedSession,3x3,1788091200000,13500,,R U R'
        """.trimIndent()
        val validStream = java.io.ByteArrayInputStream(validCsv.toByteArray(Charsets.UTF_8))
        val validResult = viewModel.importSolvesFromStream(validStream)

        assertTrue(validResult is CsvImportStatus.Success)
        val successResult = validResult as CsvImportStatus.Success
        assertEquals(1, successResult.importedCount)

        val inDb = database.solveDao().getSolveById("csv-solve-1")
        assertNotNull(inDb)
        assertEquals(13500L, inDb?.durationMs)
    }

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
