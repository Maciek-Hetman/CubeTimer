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
import com.maciekhetman.cubetimer.data.session.SessionManagerImpl
import com.maciekhetman.cubetimer.data.session.SessionRepositoryImpl
import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Penalty
import com.maciekhetman.cubetimer.model.SolveTime
import com.maciekhetman.cubetimer.model.StatsFilter
import com.maciekhetman.cubetimer.model.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HistoryEmpiricalGateChallengeTest {

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
            defaultDispatcher = testDispatcher
        )
    }

    // =========================================================================
    // TASK 1: Chunked pagination and infinite scroll boundary tests
    // Boundaries: 0 solves, 1 solve, 49 solves, 50 solves, 100 solves, 250 solves.
    // Verify offset calculations and deduplication in HistoryViewModel.
    // =========================================================================

    @Test
    fun testPaginationBoundary0Solves() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(0, state.solves.size)
        assertEquals(0, state.totalCount)
        assertFalse("hasMore should be false for 0 solves", state.hasMore)
        assertFalse(state.isLoading)
        assertFalse(state.isLoadingMore)

        // Calling loadMore should be a no-op
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(0, vm.uiState.value.solves.size)
        assertFalse(vm.uiState.value.hasMore)
    }

    @Test
    fun testPaginationBoundary1Solve() = runTest(testDispatcher) {
        val solve = SolveEntity(
            id = "solve-single",
            ownerId = "guest",
            event = "3x3",
            durationMs = 12500L,
            penalty = "none",
            solvedAt = "2026-08-30T10:00:00.100Z",
            scramble = "R U R'",
            version = 0L
        )
        database.solveDao().insert(solve)

        val vm = createViewModel()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(1, state.solves.size)
        assertEquals("solve-single", state.solves.first().id)
        assertEquals(1, state.totalCount)
        assertFalse("hasMore should be false for 1 solve (< 50)", state.hasMore)

        // loadMore is no-op
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.solves.size)
    }

    @Test
    fun testPaginationBoundary49Solves() = runTest(testDispatcher) {
        val baseTime = Instant.parse("2026-08-30T10:00:00.100Z")
        val solves = (0 until 49).map { i ->
            SolveEntity(
                id = "solve-$i",
                ownerId = "guest",
                event = "3x3",
                durationMs = 10000L + i,
                penalty = "none",
                solvedAt = baseTime.plus(i.toLong(), ChronoUnit.SECONDS).toString(),
                scramble = "R U #$i",
                version = 0L
            )
        }
        database.solveDao().insertAll(solves)

        val vm = createViewModel()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(49, state.solves.size)
        assertEquals(49, state.totalCount)
        assertFalse("hasMore should be false for 49 solves (PAGE_SIZE = 50)", state.hasMore)
        assertEquals("solve-48", state.solves.first().id)
        assertEquals("solve-0", state.solves.last().id)
    }

    @Test
    fun testPaginationBoundary50Solves() = runTest(testDispatcher) {
        val baseTime = Instant.parse("2026-08-30T10:00:00.100Z")
        val solves = (0 until 50).map { i ->
            SolveEntity(
                id = "solve-$i",
                ownerId = "guest",
                event = "3x3",
                durationMs = 10000L + i,
                penalty = "none",
                solvedAt = baseTime.plus(i.toLong(), ChronoUnit.SECONDS).toString(),
                scramble = "R U #$i",
                version = 0L
            )
        }
        database.solveDao().insertAll(solves)

        val vm = createViewModel()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(50, state.solves.size)
        assertEquals(50, state.totalCount)
        assertFalse("hasMore should be false when all 50 solves are loaded", state.hasMore)

        vm.loadMore()
        advanceUntilIdle()
        assertEquals(50, vm.uiState.value.solves.size)
    }

    @Test
    fun testPaginationBoundary100Solves() = runTest(testDispatcher) {
        val baseTime = Instant.parse("2026-08-30T10:00:00.100Z")
        val solves = (0 until 100).map { i ->
            SolveEntity(
                id = "solve-$i",
                ownerId = "guest",
                event = "3x3",
                durationMs = 10000L + i,
                penalty = "none",
                solvedAt = baseTime.plus(i.toLong(), ChronoUnit.SECONDS).toString(),
                scramble = "R U #$i",
                version = 0L
            )
        }
        database.solveDao().insertAll(solves)

        val vm = createViewModel()
        advanceUntilIdle()

        // Page 1 loaded (50 items)
        assertEquals(50, vm.uiState.value.solves.size)
        assertEquals(100, vm.uiState.value.totalCount)
        assertTrue("hasMore should be true after page 1 of 100", vm.uiState.value.hasMore)
        assertEquals("solve-99", vm.uiState.value.solves.first().id)
        assertEquals("solve-50", vm.uiState.value.solves.last().id)

        // Load Page 2 (next 50 items)
        vm.loadMore()
        advanceUntilIdle()

        assertEquals(100, vm.uiState.value.solves.size)
        assertEquals("solve-99", vm.uiState.value.solves.first().id)
        assertEquals("solve-0", vm.uiState.value.solves.last().id)
        assertFalse("hasMore should be false after page 2 of 100", vm.uiState.value.hasMore)
    }

    @Test
    fun testPaginationBoundary250SolvesAndOffsetDeduplication() = runTest(testDispatcher) {
        val baseTime = Instant.parse("2026-08-30T10:00:00.100Z")
        val solves = (0 until 250).map { i ->
            SolveEntity(
                id = "s-$i",
                ownerId = "guest",
                event = "3x3",
                durationMs = 10000L + i,
                penalty = "none",
                solvedAt = baseTime.plus(i.toLong(), ChronoUnit.SECONDS).toString(),
                scramble = "R U #$i",
                version = 0L
            )
        }
        database.solveDao().insertAll(solves)

        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals(50, vm.uiState.value.solves.size)
        assertEquals(250, vm.uiState.value.totalCount)
        assertTrue(vm.uiState.value.hasMore)

        // Load remaining pages: 50 -> 100 -> 150 -> 200 -> 250
        for (expectedCount in listOf(100, 150, 200, 250)) {
            vm.loadMore()
            advanceUntilIdle()
            assertEquals(expectedCount, vm.uiState.value.solves.size)
        }

        assertFalse("hasMore should be false after all 250 loaded", vm.uiState.value.hasMore)
        assertEquals("s-249", vm.uiState.value.solves.first().id)
        assertEquals("s-0", vm.uiState.value.solves.last().id)

        // Verify deduplication: all 250 IDs must be strictly unique
        val ids = vm.uiState.value.solves.map { it.id }
        assertEquals(250, ids.toSet().size)
    }

    @Test
    fun testMidScrollInsertDeduplicationMechanics() = runTest(testDispatcher) {
        val baseTime = Instant.parse("2026-08-30T10:00:00.100Z")
        val initialSolves = (0 until 60).map { i ->
            SolveEntity(
                id = "initial-$i",
                ownerId = "guest",
                event = "3x3",
                durationMs = 10000L + i,
                penalty = "none",
                solvedAt = baseTime.plus(i.toLong(), ChronoUnit.SECONDS).toString(),
                scramble = "R",
                version = 0L
            )
        }
        database.solveDao().insertAll(initialSolves)

        val vm = createViewModel()
        advanceUntilIdle()

        // Page 1 loaded: 50 solves (initial-59 down to initial-10)
        assertEquals(50, vm.uiState.value.solves.size)

        // Simulate concurrent insert of 1 new solve with a newer timestamp
        val newSolve = SolveEntity(
            id = "new-top-solve",
            ownerId = "guest",
            event = "3x3",
            durationMs = 9000L,
            penalty = "none",
            solvedAt = baseTime.plus(100L, ChronoUnit.SECONDS).toString(),
            scramble = "U",
            version = 0L
        )
        database.solveDao().insert(newSolve)

        // Now trigger loadMore()
        vm.loadMore()
        advanceUntilIdle()

        // Verify deduplication prevented duplicates and list contains no duplicate IDs
        val allIds = vm.uiState.value.solves.map { it.id }
        assertEquals("IDs in list must be distinct", allIds.toSet().size, allIds.size)
    }

    // =========================================================================
    // TASK 2: Optimistic actions and sync outbox mutations
    // Penalty updates (+2, DNF, NONE), solve deletion with undo, clear history with undo,
    // and verify outbox mutation enqueuing for authenticated users vs guest.
    // =========================================================================

    @Test
    fun testOptimisticPenaltyTransitionsWithOutboxEnqueuing() = runTest(testDispatcher) {
        // Authenticate user
        val testUser = User(id = "user-auth-1", email = "test@cubesync.com")
        fakeAuthManager.setAuthenticated(testUser)

        val initialSolve = SolveEntity(
            id = "solve-pen-test",
            ownerId = "user-auth-1",
            event = "3x3",
            durationMs = 11000L,
            penalty = "none",
            solvedAt = "2026-08-30T10:00:00.100Z",
            scramble = "R U R'",
            version = 1L
        )
        database.solveDao().insert(initialSolve)

        val vm = createViewModel()
        advanceUntilIdle()

        val solveTime = vm.uiState.value.solves.first()
        assertEquals(Penalty.NONE, solveTime.penalty)

        // 1. Transition NONE -> PLUS_TWO
        vm.updateSolvePenalty(solveTime, Penalty.PLUS_TWO)
        // Check optimistic state on solves StateFlow immediately
        assertEquals(Penalty.PLUS_TWO, vm.solves.value.first().penalty)
        advanceUntilIdle()
        assertEquals(Penalty.PLUS_TWO, vm.uiState.value.solves.first().penalty)

        // Verify Room persistence
        val inDbPlusTwo = database.solveDao().getSolveById("solve-pen-test")
        assertEquals("plus_two", inDbPlusTwo?.penalty)

        // Verify outbox mutation enqueued
        val outbox1 = database.syncOutboxDao().getPendingMutations(ownerId = "user-auth-1", limit = 10)
        assertEquals(1, outbox1.size)
        assertEquals("upsert", outbox1[0].action)
        assertEquals("solve-pen-test", outbox1[0].entityId)
        assertTrue(outbox1[0].payloadJson?.contains("\"penalty\":\"plus_two\"") == true)

        // 2. Transition PLUS_TWO -> DNF
        val currentSolve = vm.uiState.value.solves.first()
        vm.updateSolvePenalty(currentSolve, Penalty.DNF)
        assertEquals(Penalty.DNF, vm.solves.value.first().penalty)
        advanceUntilIdle()
        assertEquals(Penalty.DNF, vm.uiState.value.solves.first().penalty)

        val inDbDnf = database.solveDao().getSolveById("solve-pen-test")
        assertEquals("dnf", inDbDnf?.penalty)

        val outbox2 = database.syncOutboxDao().getPendingMutations(ownerId = "user-auth-1", limit = 10)
        assertEquals(2, outbox2.size)
        assertEquals("upsert", outbox2[1].action)
        assertTrue(outbox2[1].payloadJson?.contains("\"penalty\":\"dnf\"") == true)

        // 3. Transition DNF -> NONE
        val dnfSolve = vm.uiState.value.solves.first()
        vm.updateSolvePenalty(dnfSolve, Penalty.NONE)
        assertEquals(Penalty.NONE, vm.solves.value.first().penalty)
        advanceUntilIdle()
        assertEquals(Penalty.NONE, vm.uiState.value.solves.first().penalty)

        val inDbNone = database.solveDao().getSolveById("solve-pen-test")
        assertEquals("none", inDbNone?.penalty)

        val outbox3 = database.syncOutboxDao().getPendingMutations(ownerId = "user-auth-1", limit = 10)
        assertEquals(3, outbox3.size)
        assertEquals("upsert", outbox3[2].action)
        assertTrue(outbox3[2].payloadJson?.contains("\"penalty\":\"none\"") == true)
    }

    @Test
    fun testGuestPenaltyUpdateDoesNotEnqueueOutbox() = runTest(testDispatcher) {
        fakeAuthManager.setGuest()

        val solve = SolveEntity(
            id = "guest-solve",
            ownerId = "guest",
            event = "3x3",
            durationMs = 15000L,
            penalty = "none",
            solvedAt = "2026-08-30T10:00:00.100Z",
            scramble = "R",
            version = 0L
        )
        database.solveDao().insert(solve)

        val vm = createViewModel()
        advanceUntilIdle()

        val item = vm.uiState.value.solves.first()
        vm.updateSolvePenalty(item, Penalty.PLUS_TWO)
        advanceUntilIdle()

        assertEquals(Penalty.PLUS_TWO, vm.uiState.value.solves.first().penalty)
        // Outbox must remain empty for guests
        val outbox = database.syncOutboxDao().getPendingMutations(ownerId = "guest", limit = 10)
        assertTrue("Guest mutations should not be enqueued in outbox", outbox.isEmpty())
    }

    @Test
    fun testSolveDeletionWithUndoAndOutbox() = runTest(testDispatcher) {
        val testUser = User(id = "user-del-test", email = "del@test.com")
        fakeAuthManager.setAuthenticated(testUser)

        val s1 = SolveEntity(
            id = "del-s1",
            ownerId = "user-del-test",
            event = "3x3",
            durationMs = 12000L,
            penalty = "none",
            solvedAt = "2026-08-30T10:00:00.100Z",
            scramble = "R",
            version = 1L
        )
        val s2 = SolveEntity(
            id = "del-s2",
            ownerId = "user-del-test",
            event = "3x3",
            durationMs = 13000L,
            penalty = "none",
            solvedAt = "2026-08-30T10:01:00.100Z",
            scramble = "U",
            version = 1L
        )
        database.solveDao().insertAll(listOf(s1, s2))

        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals(2, vm.uiState.value.solves.size)
        val solveToDelete = vm.uiState.value.solves.first { it.id == "del-s2" }

        // Test effect emission for undo snackbar
        vm.effects.test {
            vm.deleteSolve(solveToDelete)
            // solves StateFlow updated immediately
            assertEquals(1, vm.solves.value.size)
            assertEquals("del-s1", vm.solves.value.first().id)

            val effect = awaitItem()
            assertTrue("Expected ShowUndoSnackbar effect", effect is HistoryUiEffect.ShowUndoSnackbar)
            val snackbarEffect = effect as HistoryUiEffect.ShowUndoSnackbar
            assertEquals("del-s2", snackbarEffect.solve.id)
            assertEquals(0, snackbarEffect.originalIndex)
        }
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.solves.size)
        assertEquals(1, vm.uiState.value.totalCount)

        // Room DB soft-deleted
        val softDeleted = database.solveDao().getSolveById("del-s2")
        assertNotNull(softDeleted?.deletedAt)

        // Outbox mutation enqueued
        val outbox = database.syncOutboxDao().getPendingMutations(ownerId = "user-del-test", limit = 10)
        assertEquals(1, outbox.size)
        assertEquals("delete", outbox[0].action)
        assertEquals("del-s2", outbox[0].entityId)

        // Test Undo
        vm.undoDelete()
        advanceUntilIdle()

        // Restored in UI
        assertEquals(2, vm.uiState.value.solves.size)
        assertEquals(2, vm.uiState.value.totalCount)
        assertEquals("del-s2", vm.uiState.value.solves[0].id)
        assertEquals("del-s1", vm.uiState.value.solves[1].id)

        // Room DB restored (deletedAt = null)
        val restored = database.solveDao().getSolveById("del-s2")
        assertNull(restored?.deletedAt)

        // Outbox has upsert mutation for restore
        val outboxAfterUndo = database.syncOutboxDao().getPendingMutations(ownerId = "user-del-test", limit = 10)
        assertEquals(2, outboxAfterUndo.size)
        assertEquals("upsert", outboxAfterUndo[1].action)
        assertEquals("del-s2", outboxAfterUndo[1].entityId)
    }

    @Test
    fun testClearHistoryWithUndoAndOutbox() = runTest(testDispatcher) {
        val testUser = User(id = "user-clear-test", email = "clear@test.com")
        fakeAuthManager.setAuthenticated(testUser)

        val solves = (1..3).map { i ->
            SolveEntity(
                id = "clear-s$i",
                ownerId = "user-clear-test",
                event = "3x3",
                durationMs = 10000L + i * 1000,
                penalty = "none",
                solvedAt = "2026-08-30T10:0$i:00.100Z",
                scramble = "R$i",
                version = 1L
            )
        }
        database.solveDao().insertAll(solves)

        val vm = createViewModel()
        advanceUntilIdle()
        assertEquals(3, vm.uiState.value.solves.size)

        vm.clearHistory()
        advanceUntilIdle()

        assertEquals(0, vm.uiState.value.solves.size)
        assertEquals(0, vm.uiState.value.totalCount)
        assertFalse(vm.uiState.value.hasMore)

        // Verify outbox has 3 delete mutations
        val deleteMutations = database.syncOutboxDao().getPendingMutations(ownerId = "user-clear-test", limit = 10)
        assertEquals(3, deleteMutations.size)
        assertTrue(deleteMutations.all { it.action == "delete" })

        // Verify undoClearHistory restores all solves
        vm.undoClearHistory()
        advanceUntilIdle()

        assertEquals(3, vm.uiState.value.solves.size)
        assertEquals(3, vm.uiState.value.totalCount)

        // Verify in DB all 3 are active
        val activeSolves = database.solveDao().getAllActiveSolvesForOwner("user-clear-test")
        assertEquals(3, activeSolves.size)
    }

    // =========================================================================
    // TASK 3: Prior best duration calculation across timestamps, penalties (+2, DNF),
    // and empty history.
    // =========================================================================

    @Test
    fun testPriorBestCalculationEmptyHistory() = runTest(testDispatcher) {
        val timestamp = Instant.parse("2026-08-30T10:00:00.123Z").toEpochMilli()
        val iso = CubeTypeConverters.epochMillisToIso(timestamp)
        val solve = SolveTime(
            id = "first-solve",
            timeInMillis = 14500L,
            penalty = Penalty.NONE,
            timestamp = timestamp,
            mode = Mode.CUBE_3x3
        )
        database.solveDao().insert(
            SolveEntity(
                id = solve.id,
                ownerId = "guest",
                event = "3x3",
                durationMs = solve.timeInMillis,
                penalty = "none",
                solvedAt = iso,
                scramble = "R",
                version = 0L
            )
        )

        val vm = createViewModel()
        advanceUntilIdle()

        vm.selectSolveForDetail(solve)
        advanceUntilIdle()

        val detail = vm.selectedSolveDetail.value
        assertNotNull(detail)
        assertNull("Prior best time must be null for the very first solve", detail?.priorBestTime)
        assertTrue("First non-DNF solve is always considered PB", detail?.isPb == true)
        assertNull("pbDelta must be null when there was no previous PB", detail?.pbDelta)
        assertEquals(1, detail?.solveNumber)
    }

    @Test
    fun testPriorBestCalculationWithPenaltiesPlusTwoAndDnf() = runTest(testDispatcher) {
        val t0 = "2026-08-30T10:00:00.100Z"
        val t1 = "2026-08-30T10:01:00.100Z"
        val t2 = "2026-08-30T10:02:00.100Z"
        val t3 = "2026-08-30T10:03:00.100Z"
        val t4 = "2026-08-30T10:04:00.100Z"

        // Solve 0: 15.00s clean at t0
        database.solveDao().insert(
            SolveEntity(id = "s0", ownerId = "guest", event = "3x3", durationMs = 15000L, penalty = "none", solvedAt = t0, scramble = "R", version = 0L)
        )
        // Solve 1: 10.00s raw with +2 penalty = 12.00s effective at t1 (New PB!)
        database.solveDao().insert(
            SolveEntity(id = "s1", ownerId = "guest", event = "3x3", durationMs = 10000L, penalty = "plus_two", solvedAt = t1, scramble = "R U", version = 0L)
        )
        // Solve 2: 8.00s raw with DNF at t2 (Must be ignored for prior best!)
        database.solveDao().insert(
            SolveEntity(id = "s2", ownerId = "guest", event = "3x3", durationMs = 8000L, penalty = "dnf", solvedAt = t2, scramble = "R U2", version = 0L)
        )
        // Solve 3: 13.00s clean at t3 (Slower than 12.00s effective PB -> Not a PB)
        database.solveDao().insert(
            SolveEntity(id = "s3", ownerId = "guest", event = "3x3", durationMs = 13000L, penalty = "none", solvedAt = t3, scramble = "R U'", version = 0L)
        )
        // Solve 4: 9.00s clean at t4 (Beats 12.00s PB by 3.00s -> PB!)
        database.solveDao().insert(
            SolveEntity(id = "s4", ownerId = "guest", event = "3x3", durationMs = 9000L, penalty = "none", solvedAt = t4, scramble = "R U R'", version = 0L)
        )

        val vm = createViewModel()
        advanceUntilIdle()

        // Check Solve 1 (+2 penalty): Effective duration = 12000L, prior best = 15000L, isPb = true, delta = 3000L
        val solve1 = vm.uiState.value.solves.first { it.id == "s1" }
        vm.selectSolveForDetail(solve1)
        advanceUntilIdle()

        val detail1 = vm.selectedSolveDetail.value
        assertNotNull(detail1)
        assertEquals(15000L, detail1?.priorBestTime)
        assertTrue("Solve 1 (12s effective vs 15s) must be PB", detail1?.isPb == true)
        assertEquals(3000L, detail1?.pbDelta)
        assertEquals(2, detail1?.solveNumber)

        // Check Solve 2 (DNF): Cannot be a PB
        val solve2 = vm.uiState.value.solves.first { it.id == "s2" }
        vm.selectSolveForDetail(solve2)
        advanceUntilIdle()

        val detail2 = vm.selectedSolveDetail.value
        assertNotNull(detail2)
        assertEquals(12000L, detail2?.priorBestTime) // DNF ignored, prior best remains 12000L
        assertFalse("DNF solve can never be a PB", detail2?.isPb == true)
        assertNull(detail2?.pbDelta)
        assertEquals(3, detail2?.solveNumber)

        // Check Solve 3: 13.00s vs prior best 12.00s (+2 from s1)
        val solve3 = vm.uiState.value.solves.first { it.id == "s3" }
        vm.selectSolveForDetail(solve3)
        advanceUntilIdle()

        val detail3 = vm.selectedSolveDetail.value
        assertNotNull(detail3)
        assertEquals(12000L, detail3?.priorBestTime)
        assertFalse("13.00s is slower than prior best 12.00s", detail3?.isPb == true)
        assertNull(detail3?.pbDelta)
        assertEquals(4, detail3?.solveNumber)

        // Check Solve 4: 9.00s vs prior best 12.00s -> New PB with delta 3000L
        val solve4 = vm.uiState.value.solves.first { it.id == "s4" }
        vm.selectSolveForDetail(solve4)
        advanceUntilIdle()

        val detail4 = vm.selectedSolveDetail.value
        assertNotNull(detail4)
        assertEquals(12000L, detail4?.priorBestTime)
        assertTrue("9.00s is faster than prior best 12.00s", detail4?.isPb == true)
        assertEquals(3000L, detail4?.pbDelta)
        assertEquals(5, detail4?.solveNumber)
    }

    // =========================================================================
    // EMPIRICAL BUG DEMONSTRATION:
    // Demonstrates the variable-length ISO timestamp bug in SQLite string comparison
    // =========================================================================

    @Test
    fun testEmpiricalProofOfTimestampVariableLengthBugInRoom() = runTest(testDispatcher) {
        // Solve A occurred at exactly 10:00:00.000 (stored with variable length "2026-08-30T10:00:00Z")
        val solveA = SolveEntity(
            id = "solve-A",
            ownerId = "guest",
            event = "3x3",
            durationMs = 15000L,
            penalty = "none",
            solvedAt = "2026-08-30T10:00:00Z",
            scramble = "R",
            version = 0L
        )
        // Solve B occurred 500ms later at 10:00:00.500 (stored as "2026-08-30T10:00:00.500Z")
        val solveB = SolveEntity(
            id = "solve-B",
            ownerId = "guest",
            event = "3x3",
            durationMs = 12000L,
            penalty = "none",
            solvedAt = "2026-08-30T10:00:00.500Z",
            scramble = "U",
            version = 0L
        )
        database.solveDao().insertAll(listOf(solveA, solveB))

        // In SQLite: 'Z' (ASCII 90) > '.' (ASCII 46).
        // Therefore: "2026-08-30T10:00:00Z" < "2026-08-30T10:00:00.500Z" evaluates to FALSE!
        val priorForB = database.solveDao().getPriorBestSolveDuration(
            ownerId = "guest",
            event = "3x3",
            solvedAt = "2026-08-30T10:00:00.500Z"
        )
        // EMPIRICALLY CONFIRMED BUG: priorForB is null, even though solveA happened 500ms BEFORE solveB!
        assertNull("Empirical finding: SQLite string comparison omits solveA because 'Z' > '.'", priorForB)

        // EMPIRICALLY CONFIRMED BUG 2: ORDER BY solved_at DESC orders solveA BEFORE solveB!
        val ordered = database.solveDao().getSolvesPagedByEvent("guest", "3x3", limit = 10, offset = 0)
        assertEquals("solve-A", ordered[0].id) // solve-A is older but appears FIRST in DESC
        assertEquals("solve-B", ordered[1].id) // solve-B is newer but appears SECOND
    }

    private class FakeAuthManager : AuthManager {
        private val _authState = MutableStateFlow<AuthState>(AuthState.Guest)
        override val authState: StateFlow<AuthState> = _authState.asStateFlow()
        override var currentUser: User? = null

        fun setAuthenticated(user: User) {
            currentUser = user
            _authState.value = AuthState.Authenticated(user)
        }

        fun setGuest() {
            currentUser = null
            _authState.value = AuthState.Guest
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
