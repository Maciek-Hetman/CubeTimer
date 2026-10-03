package com.maciekhetman.cubetimer.viewmodel

import kotlinx.coroutines.test.TestScope
import com.maciekhetman.cubetimer.testutil.keepUiStateActive
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
import com.maciekhetman.cubetimer.model.User
import kotlinx.coroutines.CoroutineDispatcher
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
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import java.time.Instant
import java.time.temporal.ChronoUnit
import com.maciekhetman.cubetimer.testutil.insertSession
import com.maciekhetman.cubetimer.model.SessionKind

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
    private lateinit var repositoryDispatcher: HoldableDispatcher
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
        repositoryDispatcher = HoldableDispatcher(testDispatcher)

        solvesRepository = SolvesRepository(
            context = application,
            solveDao = failingSolveDao,
            sessionDao = database.sessionDao(),
            syncOutboxDao = database.syncOutboxDao(),
            database = database,
            ioDispatcher = repositoryDispatcher
        )

        sessionRepository = SessionRepositoryImpl(
            database = database,
            sessionDao = database.sessionDao(),
            syncOutboxDao = database.syncOutboxDao()
        )

        fakeAuthManager = FakeAuthManager()
        sessionManager = SessionManagerImpl(
            sessionRepository = sessionRepository,
            solveDao = failingSolveDao,
            ioDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    private fun HistoryViewModel.solvesOf(sessionId: String): List<SolveTime> =
        uiState.value.sessionGroups.single { it.session.id == sessionId }.solves

    private fun TestScope.createViewModel(): HistoryViewModel {
        return keepUiStateActive(HistoryViewModel(
            application = application,
            solvesRepository = solvesRepository,
            sessionRepository = sessionRepository,
            authManager = fakeAuthManager,
            database = database,
            sessionDao = database.sessionDao(),
            solveDao = database.solveDao(),
            syncOutboxDao = database.syncOutboxDao(),
            defaultDispatcher = testDispatcher
        ))
    }

    // =========================================================================
    // 1. OPTIMISTIC UPDATES AND ROLLBACK ON REPOSITORY FAILURE
    // =========================================================================

    @Test
    fun testPenaltyUpdateOptimismAndRollbackWhenRepositoryThrows() = runTest(testDispatcher) {
        val session = sessionRepository.insertSession("S1", Mode.CUBE_3x3, "guest")
        val solve = SolveEntity(
            id = "solve-pen-rollback",
            ownerId = "guest",
            sessionId = session.id,
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
        vm.toggleSessionExpanded(session.id)
        advanceUntilIdle()

        val initialSolveItem = vm.solvesOf(session.id).single()
        assertEquals(Penalty.NONE, initialSolveItem.penalty)

        // Step 1: Normal penalty update to PLUS_TWO succeeds
        vm.updateSolvePenalty(initialSolveItem, Penalty.PLUS_TWO)
        advanceUntilIdle()

        assertEquals(Penalty.PLUS_TWO, vm.solvesOf(session.id).single().penalty)
        assertEquals("plus_two", realSolveDao.getSolveById("solve-pen-rollback")?.penalty)

        // Step 2: Configure repository to fail on next penalty update, held back until released so
        // the optimistic state can be observed first
        failingSolveDao.shouldFailUpsert = true
        repositoryDispatcher.holding = true

        val currentItem = vm.solvesOf(session.id).single()
        vm.effects.test {
            vm.updateSolvePenalty(currentItem, Penalty.DNF)
            advanceUntilIdle()

            // The write is still pending, so the optimistic state shows DNF
            assertEquals(Penalty.DNF, vm.solvesOf(session.id).single().penalty)

            // Repository fails and rollback occurs
            repositoryDispatcher.release()
            advanceUntilIdle()

            // State rolled back to PLUS_TWO
            assertEquals(Penalty.PLUS_TWO, vm.solvesOf(session.id).single().penalty)

            // Database still has previous PLUS_TWO
            assertEquals("plus_two", realSolveDao.getSolveById("solve-pen-rollback")?.penalty)

            // UI effect received a fixed error message, not the exception text
            val effect = awaitItem()
            assertTrue("Expected ShowMessage effect", effect is HistoryUiEffect.ShowMessage)
            assertEquals("Failed to update penalty", (effect as HistoryUiEffect.ShowMessage).message)
        }

        // Step 3: Configure repository to fail on PLUS_TWO -> NONE
        repositoryDispatcher.holding = true
        vm.effects.test {
            val itemNow = vm.solvesOf(session.id).single()
            vm.updateSolvePenalty(itemNow, Penalty.NONE)
            advanceUntilIdle()

            // Optimistically NONE
            assertEquals(Penalty.NONE, vm.solvesOf(session.id).single().penalty)

            repositoryDispatcher.release()
            advanceUntilIdle()

            // Rolled back to PLUS_TWO
            assertEquals(Penalty.PLUS_TWO, vm.solvesOf(session.id).single().penalty)

            val effect = awaitItem()
            assertEquals("Failed to update penalty", (effect as HistoryUiEffect.ShowMessage).message)
        }

        // Step 4: Disable failure -> update to NONE succeeds
        failingSolveDao.shouldFailUpsert = false
        val itemRetry = vm.solvesOf(session.id).single()
        vm.updateSolvePenalty(itemRetry, Penalty.NONE)
        advanceUntilIdle()

        assertEquals(Penalty.NONE, vm.solvesOf(session.id).single().penalty)
        assertEquals("none", realSolveDao.getSolveById("solve-pen-rollback")?.penalty)
    }

    @Test
    fun testDeleteSolveOptimismAndRollbackWhenRepositoryThrows() = runTest(testDispatcher) {
        val session = sessionRepository.insertSession("S1", Mode.CUBE_3x3, "guest")
        val baseTime = Instant.parse("2026-08-30T10:00:00.000Z")
        val solves = (1..3).map { i ->
            SolveEntity(
                id = "solve-del-$i",
                ownerId = "guest",
                sessionId = session.id,
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
        vm.toggleSessionExpanded(session.id)
        advanceUntilIdle()

        assertEquals(3, vm.solvesOf(session.id).size)

        // Target solve 2 (middle solve)
        val targetSolve = vm.solvesOf(session.id).first { it.id == "solve-del-2" }

        // Configure repository to fail on softDeleteAll, held back until released
        failingSolveDao.shouldFailSoftDeleteAll = true
        repositoryDispatcher.holding = true

        vm.effects.test {
            vm.deleteSolve(targetSolve)
            advanceUntilIdle()

            // Optimistic removal: list immediately has 2 items
            assertEquals(2, vm.solvesOf(session.id).size)
            assertFalse(vm.solvesOf(session.id).any { it.id == "solve-del-2" })

            // Repository fails and rollback occurs
            repositoryDispatcher.release()
            advanceUntilIdle()

            // Solve 2 is rolled back and re-inserted at its place (most recent first)
            assertEquals(listOf("solve-del-3", "solve-del-2", "solve-del-1"), vm.solvesOf(session.id).map { it.id })

            // DB was never soft-deleted
            val inDb = realSolveDao.getSolveById("solve-del-2")
            assertNotNull(inDb)
            assertNull("DB deletedAt should remain null on failure", inDb?.deletedAt)

            // Error effect received
            val effect = awaitItem()
            assertTrue(effect is HistoryUiEffect.ShowMessage)
            assertEquals("Failed to delete solve", (effect as HistoryUiEffect.ShowMessage).message)
        }

        // Now allow delete to succeed
        failingSolveDao.shouldFailSoftDeleteAll = false
        val solveToDeleteNow = vm.solvesOf(session.id).first { it.id == "solve-del-2" }
        vm.effects.test {
            vm.deleteSolve(solveToDeleteNow)
            advanceUntilIdle()

            assertEquals(2, vm.solvesOf(session.id).size)

            val effect = awaitItem()
            assertTrue("Expected ShowUndoSnackbar on success", effect is HistoryUiEffect.ShowUndoSnackbar)
        }
    }

    @Test
    fun testUndoRestoreSolveRollbackWhenRepositoryThrows() = runTest(testDispatcher) {
        val session = sessionRepository.insertSession("S1", Mode.CUBE_3x3, "guest")
        val solve = SolveEntity(
            id = "solve-restore-fail",
            ownerId = "guest",
            sessionId = session.id,
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
        vm.toggleSessionExpanded(session.id)
        advanceUntilIdle()

        val item = vm.solvesOf(session.id).single()

        // Delete successfully
        vm.deleteSolve(item)
        advanceUntilIdle()
        assertTrue(vm.solvesOf(session.id).isEmpty())

        // Configure repository to fail on restoreSolves (upsertAll), held back until released
        failingSolveDao.shouldFailUpsertAll = true
        repositoryDispatcher.holding = true

        vm.effects.test {
            // Drain the ShowUndoSnackbar effect that was buffered from deleteSolve
            val deleteEffect = awaitItem()
            assertTrue(deleteEffect is HistoryUiEffect.ShowUndoSnackbar)

            // Call undo
            vm.restoreSolve(item)
            advanceUntilIdle()

            // Optimistic restore: solve is back in the list while the write is pending
            assertEquals(listOf("solve-restore-fail"), vm.solvesOf(session.id).map { it.id })

            repositoryDispatcher.release()
            advanceUntilIdle()

            // Rollback on failure: solve removed from the list again
            assertTrue(vm.solvesOf(session.id).isEmpty())

            // ShowMessage effect received
            val effect = awaitItem()
            assertTrue(effect is HistoryUiEffect.ShowMessage)
            assertEquals("Failed to restore solve", (effect as HistoryUiEffect.ShowMessage).message)
        }
    }

    @Test
    fun testClearHistoryRollbackWhenRepositoryThrows() = runTest(testDispatcher) {
        val session = sessionRepository.insertSession("S1", Mode.CUBE_3x3, "guest")
        val baseTime = Instant.parse("2026-08-30T10:00:00.000Z")
        val solves = (1..3).map { i ->
            SolveEntity(
                id = "clear-fail-$i",
                ownerId = "guest",
                sessionId = session.id,
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
        vm.toggleSessionExpanded(session.id)
        advanceUntilIdle()

        assertEquals(3, vm.solvesOf(session.id).size)

        // Make clear fail in DAO, held back until released
        failingSolveDao.shouldFailSoftDeleteAll = true
        repositoryDispatcher.holding = true

        vm.effects.test {
            vm.deleteAllSolves()
            advanceUntilIdle()

            // Optimistically empty
            assertTrue(vm.solvesOf(session.id).isEmpty())

            repositoryDispatcher.release()
            advanceUntilIdle()

            // Rollback on failure: all 3 restored
            assertEquals(3, vm.solvesOf(session.id).size)
            assertEquals(3, vm.uiState.value.sessionGroups.single().solveCount)

            val effect = awaitItem()
            assertTrue(effect is HistoryUiEffect.ShowMessage)
            assertEquals("Failed to clear history", (effect as HistoryUiEffect.ShowMessage).message)
        }
    }

    // =========================================================================
    // 2. STRICT SESSION & MODE DATA ISOLATION
    // =========================================================================

    @Test
    fun testStrictDataIsolationAcrossSessionsAndModes() = runTest(testDispatcher) {
        val baseTime = Instant.parse("2026-08-30T10:00:00.000Z")

        // 1. Create sessions in different events
        // Session A is the open automatic session for 3x3 and Session C for 2x2; B and D are
        // inert (manual) sessions that the automatic policy never makes active.
        val sessionA = sessionRepository.insertSession("Session A (3x3)", Mode.CUBE_3x3, "guest", kind = SessionKind.AUTOMATIC)
        val sessionB = sessionRepository.insertSession("Session B (3x3)", Mode.CUBE_3x3, "guest")
        val sessionC = sessionRepository.insertSession("Session C (2x2)", Mode.CUBE_2x2, "guest", kind = SessionKind.AUTOMATIC)
        val sessionD = sessionRepository.insertSession("Session D (Megaminx)", Mode.MEGAMINX, "guest")

        sessionManager.getActiveSessionFlow("guest", Mode.CUBE_3x3).first { it?.id == sessionA.id }
        sessionManager.getActiveSessionFlow("guest", Mode.CUBE_2x2).first { it?.id == sessionC.id }

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
        vm.uiState.first { state -> !state.isLoading }
        advanceUntilIdle()

        // 1. Invariant: the 3x3 scope lists exactly Session A and Session B, with their own solve counts
        val state3x3 = vm.uiState.value
        assertEquals(Mode.CUBE_3x3, vm.currentMode.value)
        assertEquals(setOf(sessionA.id, sessionB.id), state3x3.sessionGroups.map { it.session.id }.toSet())
        assertEquals(10, state3x3.sessionGroups.first { it.session.id == sessionA.id }.solveCount)
        assertEquals(20, state3x3.sessionGroups.first { it.session.id == sessionB.id }.solveCount)

        // 2. Invariant: an expanded session returns exactly its own solves without leakage
        vm.toggleSessionExpanded(sessionA.id)
        vm.toggleSessionExpanded(sessionB.id)
        advanceUntilIdle()

        val expanded = vm.uiState.value.sessionGroups
        val groupA = expanded.first { it.session.id == sessionA.id }
        val groupB = expanded.first { it.session.id == sessionB.id }
        assertEquals(10, groupA.solves.size)
        assertTrue(groupA.solves.all { it.sessionId == sessionA.id })
        assertEquals(20, groupB.solves.size)
        assertTrue(groupB.solves.all { it.sessionId == sessionB.id })
        val expandedIds = expanded.flatMap { group -> group.solves.map { it.id } }
        assertTrue(expandedIds.none { it.startsWith("s-c-") || it.startsWith("s-d-") })

        // 3. Invariant: all puzzles lists every session, each with only its own solves
        vm.setPuzzleScope(PuzzleScope.ALL_PUZZLES)
        advanceUntilIdle()

        val allPuzzles = vm.uiState.value.sessionGroups
        assertEquals(4, allPuzzles.size)
        assertEquals(
            mapOf(sessionA.id to 10, sessionB.id to 20, sessionC.id to 15, sessionD.id to 5),
            allPuzzles.associate { it.session.id to it.solveCount }
        )

        // 4. Invariant: mode switch to 2x2 isolates to Session C (15 solves)
        vm.setPuzzleScope(PuzzleScope.ACTIVE_PUZZLE)
        vm.setMode(Mode.CUBE_2x2)
        advanceUntilIdle()

        val state2x2 = vm.uiState.value
        assertEquals(Mode.CUBE_2x2, vm.currentMode.value)
        assertEquals(listOf(sessionC.id), state2x2.sessionGroups.map { it.session.id })
        assertEquals(15, state2x2.sessionGroups.single().solveCount)

        // 5. Invariant: mode switch to Megaminx isolates to Session D (5 solves)
        vm.setMode(Mode.MEGAMINX)
        advanceUntilIdle()

        val stateMega = vm.uiState.value
        assertEquals(Mode.MEGAMINX, vm.currentMode.value)
        assertEquals(listOf(sessionD.id), stateMega.sessionGroups.map { it.session.id })
        assertEquals(5, stateMega.sessionGroups.single().solveCount)
    }

    /**
     * Runs work on [delegate] like normal but, while [holding], parks it until [release]. Repository
     * writes are dispatched on it, so a held write has not touched the database yet and the
     * optimistic UI state can be observed before the write succeeds or fails.
     */
    private class HoldableDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        private val held = ArrayDeque<Runnable>()
        var holding = false

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (holding) held.addLast(block) else delegate.dispatch(context, block)
        }

        fun release() {
            holding = false
            while (held.isNotEmpty()) delegate.dispatch(EmptyCoroutineContext, held.removeFirst())
        }
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
        override suspend fun verifyEmail(token: String): AuthResult<User> = AuthResult.Success(User(id = "u1", email = "u@test.com"))
        override suspend fun resendVerificationEmail(email: String): AuthResult<Unit> = AuthResult.Success(Unit)
        override suspend fun requestPasswordReset(email: String): AuthResult<Unit> = AuthResult.Success(Unit)
        override suspend fun resetPassword(token: String, newPassword: String): AuthResult<User> = AuthResult.Success(User(id = "u1", email = "u@test.com"))
        override suspend fun logout(): AuthResult<Unit> = AuthResult.Success(Unit)
        override suspend fun adoptGuestData(userId: String) = Unit
    }
}
