package com.maciekhetman.cubetimer.viewmodel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.maciekhetman.cubetimer.data.SolvesRepository
import com.maciekhetman.cubetimer.data.auth.AuthManager
import com.maciekhetman.cubetimer.data.auth.AuthResult
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.local.dao.SolveDao
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
import java.io.IOException
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Adversarial empirical challenge test verifying:
 * 1. Optimistic update rollback on repository failure (penalties, deletions, restore, clear).
 * 2. Strict data isolation across sessions, modes, and filter transitions.
 * 3. Reactive state consistency under rapid consecutive actions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class Milestone3Gen3Challenger2RollbackAndIsolationTest {

    private lateinit var testDispatcher: TestDispatcher
    private lateinit var application: Application
    private lateinit var database: CubeDatabase
    private lateinit var realSolveDao: SolveDao
    private lateinit var failingSolveDao: FailingSolveDao
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

        realSolveDao = database.solveDao()
        failingSolveDao = FailingSolveDao(realSolveDao)

        solvesRepository = SolvesRepository(
            context = application,
            solveDao = failingSolveDao,
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
            solveDao = failingSolveDao,
            authManager = fakeAuthManager,
            ioDispatcher = testDispatcher
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
    // 1. OPTIMISTIC UPDATES AND ROLLBACK ON REPOSITORY FAILURE
    // =========================================================================

    @Test
    fun testPenaltyUpdateOptimismAndRollbackWhenRepositoryThrows() = runTest(testDispatcher) {
        val solve = SolveEntity(
            id = "solve-pen-rollback",
            ownerId = "guest",
            event = "3x3",
            durationMs = 12000L,
            penalty = "none",
            solvedAt = "2026-08-30T10:00:00.000Z",
            scramble = "R U R' U'",
            version = 0L
        )
        realSolveDao.insert(solve)

        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.solves.size)
        val initialSolveItem = vm.uiState.value.solves.first()
        assertEquals(Penalty.NONE, initialSolveItem.penalty)

        // Step 1: Normal penalty update to PLUS_TWO succeeds
        vm.updateSolvePenalty(initialSolveItem, Penalty.PLUS_TWO)
        advanceUntilIdle()

        assertEquals(Penalty.PLUS_TWO, vm.uiState.value.solves.first().penalty)
        assertEquals("plus_two", realSolveDao.getSolveById("solve-pen-rollback")?.penalty)

        // Step 2: Configure repository to fail on next penalty update
        failingSolveDao.shouldFailUpsert = true

        val currentItem = vm.uiState.value.solves.first()
        vm.effects.test {
            vm.updateSolvePenalty(currentItem, Penalty.DNF)

            // Before advanceUntilIdle, optimistic state immediately reflects DNF
            assertEquals(Penalty.DNF, vm.solves.value.first().penalty)

            // Advance coroutines: repository fails and rollback occurs
            advanceUntilIdle()

            // State rolled back to PLUS_TWO
            assertEquals(Penalty.PLUS_TWO, vm.solves.value.first().penalty)
            assertEquals(Penalty.PLUS_TWO, vm.uiState.value.solves.first().penalty)

            // Database still has previous PLUS_TWO
            assertEquals("plus_two", realSolveDao.getSolveById("solve-pen-rollback")?.penalty)

            // UI effect received error message
            val effect = awaitItem()
            assertTrue("Expected ShowMessage effect", effect is HistoryUiEffect.ShowMessage)
            assertTrue(
                "Effect message should contain failure reason",
                (effect as HistoryUiEffect.ShowMessage).message.contains("Simulated upsert failure")
            )
        }

        // Step 3: Configure repository to fail on PLUS_TWO -> NONE
        vm.effects.test {
            val itemNow = vm.uiState.value.solves.first()
            vm.updateSolvePenalty(itemNow, Penalty.NONE)

            // Optimistically NONE
            assertEquals(Penalty.NONE, vm.solves.value.first().penalty)

            advanceUntilIdle()

            // Rolled back to PLUS_TWO
            assertEquals(Penalty.PLUS_TWO, vm.solves.value.first().penalty)
            assertEquals(Penalty.PLUS_TWO, vm.uiState.value.solves.first().penalty)

            val effect = awaitItem()
            assertTrue((effect as HistoryUiEffect.ShowMessage).message.contains("Simulated upsert failure"))
        }

        // Step 4: Disable failure -> update to NONE succeeds
        failingSolveDao.shouldFailUpsert = false
        val itemRetry = vm.uiState.value.solves.first()
        vm.updateSolvePenalty(itemRetry, Penalty.NONE)
        advanceUntilIdle()

        assertEquals(Penalty.NONE, vm.uiState.value.solves.first().penalty)
        assertEquals("none", realSolveDao.getSolveById("solve-pen-rollback")?.penalty)
    }

    @Test
    fun testDeleteSolveOptimismAndRollbackWhenRepositoryThrows() = runTest(testDispatcher) {
        val baseTime = Instant.parse("2026-08-30T10:00:00.000Z")
        val solves = (1..3).map { i ->
            SolveEntity(
                id = "solve-del-$i",
                ownerId = "guest",
                event = "3x3",
                durationMs = 10000L + i * 1000,
                penalty = "none",
                solvedAt = baseTime.plus(i.toLong(), ChronoUnit.MINUTES).toString(),
                scramble = "R$i",
                version = 0L
            )
        }
        realSolveDao.insertAll(solves)

        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals(3, vm.uiState.value.solves.size)
        assertEquals(3, vm.uiState.value.totalCount)

        // Target solve 2 (middle solve, index 1)
        val targetSolve = vm.uiState.value.solves.first { it.id == "solve-del-2" }

        // Configure repository to fail on softDeleteAll
        failingSolveDao.shouldFailSoftDeleteAll = true

        vm.effects.test {
            vm.deleteSolve(targetSolve)

            // Optimistic removal: list immediately has 2 items
            assertEquals(2, vm.solves.value.size)
            assertFalse(vm.solves.value.any { it.id == "solve-del-2" })
            assertEquals(2, vm.totalCount.value)

            // Run coroutines: repository fails and rollback occurs
            advanceUntilIdle()

            // Solve 2 is rolled back and re-inserted into the solves list
            assertEquals(3, vm.uiState.value.solves.size)
            assertEquals(3, vm.uiState.value.totalCount)
            assertEquals("solve-del-2", vm.uiState.value.solves[1].id)

            // DB was never soft-deleted
            val inDb = realSolveDao.getSolveById("solve-del-2")
            assertNotNull(inDb)
            assertNull("DB deletedAt should remain null on failure", inDb?.deletedAt)

            // Error effect received
            val effect = awaitItem()
            assertTrue(effect is HistoryUiEffect.ShowMessage)
            assertTrue((effect as HistoryUiEffect.ShowMessage).message.contains("Simulated soft delete failure"))
        }

        // Now allow delete to succeed
        failingSolveDao.shouldFailSoftDeleteAll = false
        val solveToDeleteNow = vm.uiState.value.solves.first { it.id == "solve-del-2" }
        vm.effects.test {
            vm.deleteSolve(solveToDeleteNow)
            advanceUntilIdle()

            assertEquals(2, vm.uiState.value.solves.size)
            assertEquals(2, vm.uiState.value.totalCount)

            val effect = awaitItem()
            assertTrue("Expected ShowUndoSnackbar on success", effect is HistoryUiEffect.ShowUndoSnackbar)
        }
    }

    @Test
    fun testUndoRestoreSolveRollbackWhenRepositoryThrows() = runTest(testDispatcher) {
        val solve = SolveEntity(
            id = "solve-restore-fail",
            ownerId = "guest",
            event = "3x3",
            durationMs = 12000L,
            penalty = "none",
            solvedAt = "2026-08-30T10:00:00.000Z",
            scramble = "R U",
            version = 0L
        )
        realSolveDao.insert(solve)

        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.solves.size)
        val item = vm.uiState.value.solves.first()

        // Delete successfully
        vm.deleteSolve(item)
        advanceUntilIdle()
        assertEquals(0, vm.uiState.value.solves.size)

        // Configure repository to fail on restoreSolves (upsertAll)
        failingSolveDao.shouldFailUpsertAll = true

        vm.effects.test {
            // Drain the ShowUndoSnackbar effect that was buffered from deleteSolve
            val deleteEffect = awaitItem()
            assertTrue(deleteEffect is HistoryUiEffect.ShowUndoSnackbar)

            // Call undo
            vm.undoDelete()

            // Optimistic restore: solve is in list before launch finishes
            assertEquals(1, vm.solves.value.size)
            assertEquals(1, vm.totalCount.value)

            advanceUntilIdle()

            // Rollback on failure: solve removed from list, totalCount decremented
            assertEquals(0, vm.solves.value.size)
            assertEquals(0, vm.uiState.value.solves.size)
            assertEquals(0, vm.totalCount.value)

            // ShowMessage effect received
            val effect = awaitItem()
            assertTrue(effect is HistoryUiEffect.ShowMessage)
            assertTrue((effect as HistoryUiEffect.ShowMessage).message.contains("Simulated upsertAll failure"))
        }
    }

    @Test
    fun testClearHistoryRollbackWhenRepositoryThrows() = runTest(testDispatcher) {
        val baseTime = Instant.parse("2026-08-30T10:00:00.000Z")
        val solves = (1..3).map { i ->
            SolveEntity(
                id = "clear-fail-$i",
                ownerId = "guest",
                event = "3x3",
                durationMs = 11000L + i * 500,
                penalty = "none",
                solvedAt = baseTime.plus(i.toLong(), ChronoUnit.MINUTES).toString(),
                scramble = "R$i",
                version = 0L
            )
        }
        realSolveDao.insertAll(solves)

        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals(3, vm.uiState.value.solves.size)
        assertEquals(3, vm.uiState.value.totalCount)

        // Make clear fail in DAO
        failingSolveDao.shouldFailSoftDeleteAll = true

        vm.effects.test {
            vm.clearHistory()

            // Optimistically empty
            assertEquals(0, vm.solves.value.size)
            assertEquals(0, vm.totalCount.value)

            advanceUntilIdle()

            // Rollback on failure: all 3 restored
            assertEquals(3, vm.uiState.value.solves.size)
            assertEquals(3, vm.uiState.value.totalCount)
            assertEquals(3, vm.solves.value.size)

            val effect = awaitItem()
            assertTrue(effect is HistoryUiEffect.ShowMessage)
            assertTrue((effect as HistoryUiEffect.ShowMessage).message.contains("Simulated soft delete failure"))
        }
    }

    // =========================================================================
    // 2. STRICT SESSION & MODE DATA ISOLATION
    // =========================================================================

    @Test
    fun testStrictDataIsolationAcrossSessionsAndModes() = runTest(testDispatcher) {
        val baseTime = Instant.parse("2026-08-30T10:00:00.000Z")

        // 1. Create sessions in different events
        val sessionA = sessionRepository.createManualSession("Session A (3x3)", Mode.CUBE_3x3, "guest")
        val sessionB = sessionRepository.createManualSession("Session B (3x3)", Mode.CUBE_3x3, "guest")
        val sessionC = sessionRepository.createManualSession("Session C (2x2)", Mode.CUBE_2x2, "guest")
        val sessionD = sessionRepository.createManualSession("Session D (Megaminx)", Mode.MEGAMINX, "guest")

        // Set Session A active for 3x3
        sessionManager.setActiveSession("guest", Mode.CUBE_3x3, sessionA.id)
        sessionManager.getActiveSessionFlow("guest", Mode.CUBE_3x3).first { it?.id == sessionA.id }

        // Set Session C active for 2x2
        sessionManager.setActiveSession("guest", Mode.CUBE_2x2, sessionC.id)

        // Seed Solves:
        // Session A: 10 solves (3x3)
        val solvesA = (0 until 10).map { i ->
            SolveEntity(
                id = "s-a-$i",
                ownerId = "guest",
                sessionId = sessionA.id,
                event = "3x3",
                durationMs = 12000L + i,
                penalty = "none",
                solvedAt = baseTime.plus(i.toLong(), ChronoUnit.MINUTES).toString(),
                scramble = "R U",
                version = 0L
            )
        }
        realSolveDao.insertAll(solvesA)

        // Session B: 20 solves (3x3)
        val solvesB = (0 until 20).map { i ->
            SolveEntity(
                id = "s-b-$i",
                ownerId = "guest",
                sessionId = sessionB.id,
                event = "3x3",
                durationMs = 11000L + i,
                penalty = "none",
                solvedAt = baseTime.plus((50 + i).toLong(), ChronoUnit.MINUTES).toString(),
                scramble = "U R",
                version = 0L
            )
        }
        realSolveDao.insertAll(solvesB)

        // Session C: 15 solves (2x2)
        val solvesC = (0 until 15).map { i ->
            SolveEntity(
                id = "s-c-$i",
                ownerId = "guest",
                sessionId = sessionC.id,
                event = "2x2",
                durationMs = 4000L + i,
                penalty = "none",
                solvedAt = baseTime.plus((100 + i).toLong(), ChronoUnit.MINUTES).toString(),
                scramble = "R",
                version = 0L
            )
        }
        realSolveDao.insertAll(solvesC)

        // Session D: 5 solves (Megaminx)
        val solvesD = (0 until 5).map { i ->
            SolveEntity(
                id = "s-d-$i",
                ownerId = "guest",
                sessionId = sessionD.id,
                event = "megaminx",
                durationMs = 75000L + i,
                penalty = "none",
                solvedAt = baseTime.plus((200 + i).toLong(), ChronoUnit.MINUTES).toString(),
                scramble = "R++",
                version = 0L
            )
        }
        realSolveDao.insertAll(solvesD)

        val vm = createViewModel()
        vm.setFilter(StatsFilter.ActiveSession)
        vm.uiState.first { state ->
            state.activeSession?.id == sessionA.id && !state.isLoading && state.totalCount == 10 && state.currentFilter == StatsFilter.ActiveSession
        }
        advanceUntilIdle()

        // 1. Invariant: Active Session (Session A) returns exactly 10 solves
        val stateActive = vm.uiState.value
        assertEquals(StatsFilter.ActiveSession, stateActive.currentFilter)
        assertEquals(10, stateActive.totalCount)
        assertEquals(10, stateActive.solves.size)
        assertTrue(stateActive.solves.all { it.sessionId == sessionA.id })
        assertTrue(stateActive.solves.none { it.id.startsWith("s-b-") || it.id.startsWith("s-c-") || it.id.startsWith("s-d-") })

        // 2. Invariant: Specific Session (Session B) returns exactly 20 solves without leakage
        vm.setFilter(StatsFilter.SpecificSession(sessionB.id, sessionB.name))
        advanceUntilIdle()

        val stateB = vm.uiState.value
        assertEquals(20, stateB.totalCount)
        assertEquals(20, stateB.solves.size)
        assertTrue(stateB.solves.all { it.sessionId == sessionB.id })
        assertTrue(stateB.solves.none { it.sessionId == sessionA.id || it.sessionId == sessionC.id || it.sessionId == sessionD.id })

        // 3. Invariant: All Sessions in 3x3 mode returns exactly 30 solves (10 + 20)
        // Solves from 2x2 (Session C) and Megaminx (Session D) MUST NEVER LEAK!
        vm.setFilter(StatsFilter.AllSessions)
        advanceUntilIdle()

        val stateAll3x3 = vm.uiState.value
        assertEquals(30, stateAll3x3.totalCount)
        assertEquals(30, stateAll3x3.solves.size)
        assertTrue(stateAll3x3.solves.all { it.mode == Mode.CUBE_3x3 })
        assertTrue("No 2x2 or Megaminx solves in 3x3 all solves", stateAll3x3.solves.none { it.mode == Mode.CUBE_2x2 || it.mode == Mode.MEGAMINX })

        // 4. Invariant: Mode switch to 2x2 isolates to Session C (15 solves)
        vm.setMode(Mode.CUBE_2x2)
        advanceUntilIdle()

        val state2x2 = vm.uiState.value
        assertEquals(Mode.CUBE_2x2, state2x2.currentMode)
        assertEquals(15, state2x2.totalCount)
        assertEquals(15, state2x2.solves.size)
        assertTrue(state2x2.solves.all { it.mode == Mode.CUBE_2x2 })
        assertTrue(state2x2.solves.none { it.mode == Mode.CUBE_3x3 })

        // 5. Invariant: Mode switch to Megaminx isolates to Session D (5 solves)
        vm.setMode(Mode.MEGAMINX)
        advanceUntilIdle()

        val stateMega = vm.uiState.value
        assertEquals(Mode.MEGAMINX, stateMega.currentMode)
        assertEquals(5, stateMega.totalCount)
        assertEquals(5, stateMega.solves.size)
        assertTrue(stateMega.solves.all { it.mode == Mode.MEGAMINX })
    }

    @Test
    fun testRapidAlternatingFilterSwitchesPreservesStateConsistency() = runTest(testDispatcher) {
        val sessionA = sessionRepository.createManualSession("S-A", Mode.CUBE_3x3, "guest")
        val sessionB = sessionRepository.createManualSession("S-B", Mode.CUBE_3x3, "guest")

        val sA = (1..5).map { i ->
            SolveEntity("sa-$i", "guest", sessionA.id, "3x3", 10000L + i, "none", "2026-08-30T10:0$i:00Z", "R", 0L)
        }
        val sB = (1..8).map { i ->
            SolveEntity("sb-$i", "guest", sessionB.id, "3x3", 12000L + i, "none", "2026-08-30T10:1$i:00Z", "U", 0L)
        }
        realSolveDao.insertAll(sA + sB)

        sessionManager.setActiveSession("guest", Mode.CUBE_3x3, sessionA.id)
        sessionManager.getActiveSessionFlow("guest", Mode.CUBE_3x3).first { it?.id == sessionA.id }

        val vm = createViewModel()
        advanceUntilIdle()

        // Stress: rapid switching
        for (round in 0 until 10) {
            vm.setFilter(StatsFilter.ActiveSession)
            vm.setFilter(StatsFilter.SpecificSession(sessionB.id, sessionB.name))
            vm.setFilter(StatsFilter.AllSessions)
            vm.setFilter(StatsFilter.SpecificSession(sessionA.id, sessionA.name))
            vm.setFilter(StatsFilter.AllSessions)
        }
        advanceUntilIdle()

        val finalState = vm.uiState.value
        assertEquals(StatsFilter.AllSessions, finalState.currentFilter)
        assertEquals(13, finalState.totalCount) // 5 + 8 = 13
        assertEquals(13, finalState.solves.size)
        assertFalse(finalState.isLoading)
        assertFalse(finalState.hasMore)
    }

    /**
     * Test decorator around SolveDao that can inject failures into specific operations.
     */
    private class FailingSolveDao(private val delegate: SolveDao) : SolveDao by delegate {
        var shouldFailUpsert = false
        var shouldFailUpsertAll = false
        var shouldFailSoftDeleteAll = false

        override suspend fun upsert(solve: SolveEntity): Long {
            if (shouldFailUpsert) {
                throw IOException("Simulated upsert failure on ${solve.id}")
            }
            return delegate.upsert(solve)
        }

        override suspend fun upsertAll(solves: List<SolveEntity>): List<Long> {
            if (shouldFailUpsertAll) {
                throw IOException("Simulated upsertAll failure on ${solves.size} solves")
            }
            return delegate.upsertAll(solves)
        }

        override suspend fun softDeleteAll(ids: List<String>, deletedAt: String, updatedAt: String): Int {
            if (shouldFailSoftDeleteAll) {
                throw IOException("Simulated soft delete failure on ${ids.size} solves")
            }
            return delegate.softDeleteAll(ids, deletedAt, updatedAt)
        }
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
