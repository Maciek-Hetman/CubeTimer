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
import com.maciekhetman.cubetimer.data.local.converter.CubeTypeConverters
import com.maciekhetman.cubetimer.data.local.entity.SessionEntity
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import com.maciekhetman.cubetimer.data.session.SessionManagerImpl
import com.maciekhetman.cubetimer.data.session.SessionRepositoryImpl
import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Penalty
import com.maciekhetman.cubetimer.model.SolveTime
import com.maciekhetman.cubetimer.model.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
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

    private fun seedSession(ownerId: String): String {
        val sessionId = "session-$ownerId"
        runBlocking {
            database.sessionDao().insert(
                SessionEntity(
                    id = sessionId,
                    ownerId = ownerId,
                    name = "Seeded Session",
                    event = "3x3",
                    kind = "manual",
                    startedAt = "2026-08-30T09:00:00.000Z"
                )
            )
        }
        return sessionId
    }

    private fun HistoryViewModel.expandedSolves(sessionId: String): List<SolveTime> =
        uiState.value.sessionGroups.single { it.session.id == sessionId }.solves

    private fun TestScope.createViewModel(): HistoryViewModel {
        return keepUiStateActive(HistoryViewModel(
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
        ))
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
        val sessionId = seedSession("user-auth-1")

        val initialSolve = SolveEntity(
            id = "solve-pen-test",
            ownerId = "user-auth-1",
            sessionId = sessionId,
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
        vm.expandSession(sessionId)
        advanceUntilIdle()

        val solveTime = vm.expandedSolves(sessionId).single()
        assertEquals(Penalty.NONE, solveTime.penalty)

        // 1. Transition NONE -> PLUS_TWO
        vm.updateSolvePenalty(solveTime, Penalty.PLUS_TWO)
        advanceUntilIdle()
        assertEquals(Penalty.PLUS_TWO, vm.expandedSolves(sessionId).single().penalty)

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
        val currentSolve = vm.expandedSolves(sessionId).single()
        vm.updateSolvePenalty(currentSolve, Penalty.DNF)
        advanceUntilIdle()
        assertEquals(Penalty.DNF, vm.expandedSolves(sessionId).single().penalty)

        val inDbDnf = database.solveDao().getSolveById("solve-pen-test")
        assertEquals("dnf", inDbDnf?.penalty)

        val outbox2 = database.syncOutboxDao().getPendingMutations(ownerId = "user-auth-1", limit = 10)
        assertEquals(2, outbox2.size)
        assertEquals("upsert", outbox2[1].action)
        assertTrue(outbox2[1].payloadJson?.contains("\"penalty\":\"dnf\"") == true)

        // 3. Transition DNF -> NONE
        val dnfSolve = vm.expandedSolves(sessionId).single()
        vm.updateSolvePenalty(dnfSolve, Penalty.NONE)
        advanceUntilIdle()
        assertEquals(Penalty.NONE, vm.expandedSolves(sessionId).single().penalty)

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
        val sessionId = seedSession("guest")

        val solve = SolveEntity(
            id = "guest-solve",
            ownerId = "guest",
            sessionId = sessionId,
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
        vm.expandSession(sessionId)
        advanceUntilIdle()

        val item = vm.expandedSolves(sessionId).single()
        vm.updateSolvePenalty(item, Penalty.PLUS_TWO)
        advanceUntilIdle()

        assertEquals(Penalty.PLUS_TWO, vm.expandedSolves(sessionId).single().penalty)
        // Outbox must remain empty for guests
        val outbox = database.syncOutboxDao().getPendingMutations(ownerId = "guest", limit = 10)
        assertTrue("Guest mutations should not be enqueued in outbox", outbox.isEmpty())
    }

    @Test
    fun testSolveDeletionWithUndoAndOutbox() = runTest(testDispatcher) {
        val testUser = User(id = "user-del-test", email = "del@test.com")
        fakeAuthManager.setAuthenticated(testUser)
        val sessionId = seedSession("user-del-test")

        val s1 = SolveEntity(
            id = "del-s1",
            ownerId = "user-del-test",
            sessionId = sessionId,
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
            sessionId = sessionId,
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
        vm.expandSession(sessionId)
        advanceUntilIdle()

        assertEquals(2, vm.expandedSolves(sessionId).size)
        val solveToDelete = vm.expandedSolves(sessionId).first { it.id == "del-s2" }

        // Test effect emission for undo snackbar
        vm.effects.test {
            vm.deleteSolve(solveToDelete)

            val effect = awaitItem()
            assertTrue("Expected ShowUndoSnackbar effect", effect is HistoryUiEffect.ShowUndoSnackbar)
            val snackbarEffect = effect as HistoryUiEffect.ShowUndoSnackbar
            assertEquals("del-s2", snackbarEffect.solve.id)
            assertEquals(0, snackbarEffect.originalIndex)
        }
        advanceUntilIdle()

        assertEquals(listOf("del-s1"), vm.expandedSolves(sessionId).map { it.id })

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
        assertEquals(listOf("del-s2", "del-s1"), vm.expandedSolves(sessionId).map { it.id })

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
        val sessionId = seedSession("user-clear-test")

        val solves = (1..3).map { i ->
            SolveEntity(
                id = "clear-s$i",
                ownerId = "user-clear-test",
                sessionId = sessionId,
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
        assertEquals(3, vm.uiState.value.sessionGroups.single().solveCount)

        vm.clearHistory()
        advanceUntilIdle()

        assertEquals(0, vm.uiState.value.sessionGroups.single().solveCount)

        // Verify outbox has 3 delete mutations
        val deleteMutations = database.syncOutboxDao().getPendingMutations(ownerId = "user-clear-test", limit = 10)
        assertEquals(3, deleteMutations.size)
        assertTrue(deleteMutations.all { it.action == "delete" })

        // Verify undoClearHistory restores all solves
        vm.undoClearHistory()
        advanceUntilIdle()

        assertEquals(3, vm.uiState.value.sessionGroups.single().solveCount)

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

        vm.selectSolveForDetail(solve, solveNumber = 1)
        advanceUntilIdle()

        val detail = vm.uiState.value.selectedSolve
        assertNotNull(detail)
        assertNull("Prior best time must be null for the very first solve", detail?.priorBestTime)
        assertTrue("First non-DNF solve is always considered PB", detail?.isPb == true)
        assertNull("pbDelta must be null when there was no previous PB", detail?.pbDelta)
    }

    @Test
    fun testPriorBestCalculationWithPenaltiesPlusTwoAndDnf() = runTest(testDispatcher) {
        val t0 = "2026-08-30T10:00:00.100Z"
        val t1 = "2026-08-30T10:01:00.100Z"
        val t2 = "2026-08-30T10:02:00.100Z"
        val t3 = "2026-08-30T10:03:00.100Z"
        val t4 = "2026-08-30T10:04:00.100Z"
        val sessionId = seedSession("guest")

        // Solve 0: 15.00s clean at t0
        database.solveDao().insert(
            SolveEntity(id = "s0", ownerId = "guest", sessionId = sessionId, event = "3x3", durationMs = 15000L, penalty = "none", solvedAt = t0, scramble = "R", version = 0L)
        )
        // Solve 1: 10.00s raw with +2 penalty = 12.00s effective at t1 (New PB!)
        database.solveDao().insert(
            SolveEntity(id = "s1", ownerId = "guest", sessionId = sessionId, event = "3x3", durationMs = 10000L, penalty = "plus_two", solvedAt = t1, scramble = "R U", version = 0L)
        )
        // Solve 2: 8.00s raw with DNF at t2 (Must be ignored for prior best!)
        database.solveDao().insert(
            SolveEntity(id = "s2", ownerId = "guest", sessionId = sessionId, event = "3x3", durationMs = 8000L, penalty = "dnf", solvedAt = t2, scramble = "R U2", version = 0L)
        )
        // Solve 3: 13.00s clean at t3 (Slower than 12.00s effective PB -> Not a PB)
        database.solveDao().insert(
            SolveEntity(id = "s3", ownerId = "guest", sessionId = sessionId, event = "3x3", durationMs = 13000L, penalty = "none", solvedAt = t3, scramble = "R U'", version = 0L)
        )
        // Solve 4: 9.00s clean at t4 (Beats 12.00s PB by 3.00s -> PB!)
        database.solveDao().insert(
            SolveEntity(id = "s4", ownerId = "guest", sessionId = sessionId, event = "3x3", durationMs = 9000L, penalty = "none", solvedAt = t4, scramble = "R U R'", version = 0L)
        )

        val vm = createViewModel()
        advanceUntilIdle()
        vm.expandSession(sessionId)
        advanceUntilIdle()
        val group = vm.uiState.value.sessionGroups.single()
        fun select(id: String) {
            val solve = group.solves.first { it.id == id }
            vm.selectSolveForDetail(solve, group.solveNumbers.getValue(id))
        }

        // Check Solve 1 (+2 penalty): Effective duration = 12000L, prior best = 15000L, isPb = true, delta = 3000L
        select("s1")
        advanceUntilIdle()

        val detail1 = vm.uiState.value.selectedSolve
        assertNotNull(detail1)
        assertEquals(15000L, detail1?.priorBestTime)
        assertTrue("Solve 1 (12s effective vs 15s) must be PB", detail1?.isPb == true)
        assertEquals(3000L, detail1?.pbDelta)
        assertEquals(2, detail1?.solveNumber)

        // Check Solve 2 (DNF): Cannot be a PB
        select("s2")
        advanceUntilIdle()

        val detail2 = vm.uiState.value.selectedSolve
        assertNotNull(detail2)
        assertEquals(12000L, detail2?.priorBestTime) // DNF ignored, prior best remains 12000L
        assertFalse("DNF solve can never be a PB", detail2?.isPb == true)
        assertNull(detail2?.pbDelta)
        assertEquals(3, detail2?.solveNumber)

        // Check Solve 3: 13.00s vs prior best 12.00s (+2 from s1)
        select("s3")
        advanceUntilIdle()

        val detail3 = vm.uiState.value.selectedSolve
        assertNotNull(detail3)
        assertEquals(12000L, detail3?.priorBestTime)
        assertFalse("13.00s is slower than prior best 12.00s", detail3?.isPb == true)
        assertNull(detail3?.pbDelta)
        assertEquals(4, detail3?.solveNumber)

        // Check Solve 4: 9.00s vs prior best 12.00s -> New PB with delta 3000L
        select("s4")
        advanceUntilIdle()

        val detail4 = vm.uiState.value.selectedSolve
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
        override suspend fun loginWithGoogle(idToken: String, clientId: String, nonce: String): AuthResult<User> = AuthResult.Success(User(id = "u1", email = "u@test.com"))
        override suspend fun verifyEmail(token: String): AuthResult<User> = AuthResult.Success(User(id = "u1", email = "u@test.com"))
        override suspend fun resendVerificationEmail(email: String): AuthResult<Unit> = AuthResult.Success(Unit)
        override suspend fun requestPasswordReset(email: String): AuthResult<Unit> = AuthResult.Success(Unit)
        override suspend fun resetPassword(token: String, newPassword: String): AuthResult<User> = AuthResult.Success(User(id = "u1", email = "u@test.com"))
        override suspend fun refreshSession(): AuthResult<User> = AuthResult.Success(User(id = "u1", email = "u@test.com"))
        override suspend fun logout(): AuthResult<Unit> = AuthResult.Success(Unit)
        override suspend fun adoptGuestData(userId: String) = Unit
    }
}
