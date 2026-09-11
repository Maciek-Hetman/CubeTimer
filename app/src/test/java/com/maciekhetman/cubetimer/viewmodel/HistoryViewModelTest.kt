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
            defaultDispatcher = testDispatcher
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
