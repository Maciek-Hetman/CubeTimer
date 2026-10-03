package com.maciekhetman.cubetimer.viewmodel

import android.app.Application
import android.database.sqlite.SQLiteFullException
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.data.SettingsRepository
import com.maciekhetman.cubetimer.data.SolvesRepository
import com.maciekhetman.cubetimer.data.auth.AuthManager
import com.maciekhetman.cubetimer.data.auth.AuthResult
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.local.dao.SolveDao
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import com.maciekhetman.cubetimer.data.local.mapper.toEntity
import com.maciekhetman.cubetimer.data.session.SessionManager
import com.maciekhetman.cubetimer.data.settingsDataStore
import com.maciekhetman.cubetimer.data.solvesDataStore
import com.maciekhetman.cubetimer.domain.bluetooth.SmartTimerEvent
import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Penalty
import com.maciekhetman.cubetimer.model.Session
import com.maciekhetman.cubetimer.model.TimerState
import com.maciekhetman.cubetimer.model.User
import com.maciekhetman.cubetimer.testutil.awaitCondition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A failing Room write must not escape viewModelScope (that crashes the app): it is reported through
 * [TimerViewModel.writeError] and the optimistic change is rolled back on screen right away.
 * Uses runCurrent() + polling like TimerViewModelBluetoothTest, since Room runs on real threads and a
 * running timer's display ticker would make advanceUntilIdle() loop forever.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TimerViewModelWriteFailureTest {

    private lateinit var testDispatcher: TestDispatcher
    private lateinit var application: Application
    private lateinit var database: CubeDatabase
    private lateinit var solveDao: FailingSolveDao
    private lateinit var sessionManager: FakeSessionManager
    private lateinit var viewModel: TimerViewModel

    private val session = Session(
        id = "write-failure-session",
        ownerId = "guest",
        name = "29 sep 2026 afternoon",
        event = Mode.CUBE_3x3,
        startedAt = "2026-09-29T12:00:00.000Z"
    )

    private val existingSolve = SolveEntity(
        id = "existing-solve",
        ownerId = "guest",
        sessionId = session.id,
        event = "3x3",
        durationMs = 20_000L,
        solvedAt = "2026-09-29T12:05:00.000Z"
    )

    @Before
    fun setup() {
        testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        application = ApplicationProvider.getApplicationContext()
        runBlocking {
            application.settingsDataStore.edit { it.clear() }
            application.solvesDataStore.edit { it.clear() }
        }
        database = CubeDatabase.createInMemory(application)
        runBlocking {
            database.sessionDao().upsert(session.toEntity())
            database.solveDao().upsert(existingSolve)
        }
        solveDao = FailingSolveDao(database.solveDao())
        sessionManager = FakeSessionManager(session)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        database.close()
        runBlocking { application.settingsDataStore.edit { it.clear() } }
    }

    private fun TestScope.createViewModel(): TimerViewModel {
        viewModel = TimerViewModel(
            application = application,
            repository = SolvesRepository(
                context = application,
                solveDao = solveDao,
                sessionDao = database.sessionDao(),
                syncOutboxDao = database.syncOutboxDao(),
                database = database
            ),
            settingsRepository = SettingsRepository(application),
            sessionManager = sessionManager,
            authManager = GuestAuthManager()
        )
        // The first DB emission is what a failed write rolls back to.
        awaitCondition("initial solves loaded") { viewModel.allSolves.value.map { it.id } == listOf(existingSolve.id) }
        return viewModel
    }

    private fun TimerViewModel.finishBluetoothSolve(timeMs: Long) {
        onSmartTimerEvent(SmartTimerEvent.Running)
        onSmartTimerEvent(SmartTimerEvent.Stopped(timeMs))
    }

    @Test
    fun failedSolveSave_isReportedAndRolledBackWithoutCrashing() = runTest(testDispatcher) {
        createViewModel()
        solveDao.failWrites = true

        viewModel.finishBluetoothSolve(12_345L)
        viewModel.saveSolveWithPenalty(Penalty.NONE)

        awaitCondition("write error reported") { viewModel.writeError.value != null }
        assertEquals("Couldn't save the solve", viewModel.writeError.value)
        // The optimistic solve is gone again: only the solve that is really in the DB remains.
        assertEquals(listOf(existingSolve.id), viewModel.allSolves.value.map { it.id })
        assertEquals(1, database.solveDao().getAllActiveSolvesForOwner("guest").size)
        // An unsaved solve can't be a record.
        assertNull(viewModel.recordCelebration.value)
        assertEquals(TimerState.Idle, viewModel.timerState.value)
    }

    @Test
    fun failedSessionLookup_isReportedWithoutCrashing() = runTest(testDispatcher) {
        createViewModel()
        sessionManager.fail = true

        viewModel.finishBluetoothSolve(9_000L)
        viewModel.saveSolveWithPenalty(Penalty.PLUS_TWO)

        awaitCondition("write error reported") { viewModel.writeError.value != null }
        assertEquals("Couldn't save the solve", viewModel.writeError.value)
        assertEquals(listOf(existingSolve.id), viewModel.allSolves.value.map { it.id })
    }

    @Test
    fun clearWriteError_resetsTheError() = runTest(testDispatcher) {
        createViewModel()
        solveDao.failWrites = true
        viewModel.finishBluetoothSolve(12_345L)
        viewModel.saveSolveWithPenalty(Penalty.NONE)
        awaitCondition("write error reported") { viewModel.writeError.value != null }

        viewModel.clearWriteError()

        assertNull(viewModel.writeError.value)
    }

    @Test
    fun successfulSave_reportsNoError() = runTest(testDispatcher) {
        createViewModel()

        viewModel.finishBluetoothSolve(15_000L)
        viewModel.saveSolveWithPenalty(Penalty.NONE)

        awaitCondition("solve saved") { runBlocking { database.solveDao().getAllActiveSolvesForOwner("guest").size } == 2 }
        awaitCondition("saved solve shown") { viewModel.allSolves.value.size == 2 }
        assertNull(viewModel.writeError.value)
    }
}

/** Real DAO whose writes can be switched to fail like a full disk would. */
private class FailingSolveDao(private val delegate: SolveDao) : SolveDao by delegate {
    @Volatile var failWrites = false

    override suspend fun upsert(solve: SolveEntity): Long {
        if (failWrites) throw SQLiteFullException("database or disk is full")
        return delegate.upsert(solve)
    }
}

private class FakeSessionManager(private val session: Session) : SessionManager {
    @Volatile var fail = false

    override fun getActiveSessionFlow(ownerId: String, mode: Mode): Flow<Session?> = MutableStateFlow(session)

    override suspend fun getOrCreateActiveSession(ownerId: String, mode: Mode, solveTimestamp: Long?): Session {
        if (fail) throw SQLiteFullException("database or disk is full")
        return session
    }
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
