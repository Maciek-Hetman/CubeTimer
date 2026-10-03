package com.maciekhetman.cubetimer.viewmodel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.data.SolvesRepository
import com.maciekhetman.cubetimer.data.auth.AuthManager
import com.maciekhetman.cubetimer.data.auth.AuthResult
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.local.dao.SessionDao
import com.maciekhetman.cubetimer.data.local.dao.SolveDao
import com.maciekhetman.cubetimer.data.local.dto.SessionWithStats
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import com.maciekhetman.cubetimer.data.session.SessionRepositoryImpl
import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.model.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

/**
 * HistoryViewModel only does work while History is on screen: the session list query follows the
 * lifetime of `uiState` subscribers, and the loading indicator no longer waits on the solve list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HistoryViewModelLifecycleTest {

    private companion object {
        const val UI_STATE_STOP_TIMEOUT_MILLIS = 5_000L
    }

    private lateinit var testDispatcher: TestDispatcher
    private lateinit var application: Application
    private lateinit var database: CubeDatabase
    private lateinit var sessionRepository: SessionRepositoryImpl
    private val authManager = GuestAuthManager()

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
        sessionRepository = SessionRepositoryImpl(
            database = database,
            sessionDao = database.sessionDao(),
            syncOutboxDao = database.syncOutboxDao()
        )
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    private fun createViewModel(
        sessionDao: SessionDao = database.sessionDao(),
        solveDao: SolveDao = database.solveDao()
    ): HistoryViewModel = HistoryViewModel(
        application = application,
        solvesRepository = SolvesRepository(
            context = application,
            solveDao = solveDao,
            sessionDao = sessionDao,
            syncOutboxDao = database.syncOutboxDao(),
            database = database,
            ioDispatcher = testDispatcher
        ),
        sessionRepository = sessionRepository,
        authManager = authManager,
        database = database,
        sessionDao = sessionDao,
        solveDao = solveDao,
        syncOutboxDao = database.syncOutboxDao(),
        defaultDispatcher = testDispatcher,
        ioDispatcher = testDispatcher
    )

    @Test
    fun sessionQueryRunsOnlyWhileUiStateIsCollected() = runTest(testDispatcher) {
        val sessionDao = CountingSessionDao(database.sessionDao())
        val viewModel = createViewModel(sessionDao = sessionDao)
        runCurrent()
        assertEquals("nothing collects uiState yet", 0, sessionDao.activeQueries.get())

        val collector = backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        assertEquals(1, sessionDao.activeQueries.get())

        // Leaving History: once the subscriber is gone the query is dropped after the stop timeout,
        // so solves saved on the timer screen no longer re-run it.
        collector.cancel()
        runCurrent()
        advanceTimeBy(UI_STATE_STOP_TIMEOUT_MILLIS + 1)
        runCurrent()
        assertEquals(0, sessionDao.activeQueries.get())

        val secondCollector = backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        assertEquals(1, sessionDao.activeQueries.get())
        secondCollector.cancel()
    }

    @Test
    fun loadingEndsWithTheSessionListEvenWhenSessionSolvesNeverAnswer() = runTest(testDispatcher) {
        val viewModel = createViewModel(solveDao = HangingSolveDao(database.solveDao()))
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.sessionGroups.isEmpty())
        assertFalse("an empty history must show the empty state, not a spinner", state.isLoading)
    }

    @Test
    fun constructingFromAPlainApplicationFailsInsteadOfBuildingASecondAuthManager() {
        assertThrows(ClassCastException::class.java) { HistoryViewModel(Application()) }
    }

    /** Counts the collectors of the session list query. */
    private class CountingSessionDao(private val delegate: SessionDao) : SessionDao by delegate {
        val activeQueries = AtomicInteger(0)

        override fun observeSessionsWithStats(
            ownerId: String,
            event: String?,
            kind: String?
        ): Flow<List<SessionWithStats>> = delegate.observeSessionsWithStats(ownerId, event, kind)
            .onStart { activeQueries.incrementAndGet() }
            .onCompletion { activeQueries.decrementAndGet() }
    }

    /** The per-session solve query never answers, like a query stuck behind a long write. */
    private class HangingSolveDao(private val delegate: SolveDao) : SolveDao by delegate {
        override fun observeSolvesBySessionDesc(ownerId: String, sessionId: String): Flow<List<SolveEntity>> =
            flow { awaitCancellation() }
    }

    private class GuestAuthManager : AuthManager {
        private val user = User(id = "u1", email = "u@test.com")
        override val authState: StateFlow<AuthState> = MutableStateFlow<AuthState>(AuthState.Guest).asStateFlow()
        override val currentUser: User? = null

        override suspend fun initialize() = Unit
        override suspend fun register(email: String, password: String): AuthResult<Unit> = AuthResult.Success(Unit)
        override suspend fun login(email: String, password: String): AuthResult<User> = AuthResult.Success(user)
        override suspend fun verifyEmail(token: String): AuthResult<User> = AuthResult.Success(user)
        override suspend fun resendVerificationEmail(email: String): AuthResult<Unit> = AuthResult.Success(Unit)
        override suspend fun requestPasswordReset(email: String): AuthResult<Unit> = AuthResult.Success(Unit)
        override suspend fun resetPassword(token: String, newPassword: String): AuthResult<User> = AuthResult.Success(user)
        override suspend fun logout(): AuthResult<Unit> = AuthResult.Success(Unit)
        override suspend fun adoptGuestData(userId: String) = Unit
    }
}
