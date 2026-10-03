package com.maciekhetman.cubetimer.viewmodel

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.data.SettingsRepository
import com.maciekhetman.cubetimer.data.SolvesRepository
import com.maciekhetman.cubetimer.data.auth.AuthManager
import com.maciekhetman.cubetimer.data.auth.AuthResult
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import com.maciekhetman.cubetimer.data.local.mapper.toEntity
import com.maciekhetman.cubetimer.data.session.SessionManager
import com.maciekhetman.cubetimer.data.settingsDataStore
import com.maciekhetman.cubetimer.data.solvesDataStore
import com.maciekhetman.cubetimer.domain.bluetooth.SmartTimerEvent
import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Penalty
import com.maciekhetman.cubetimer.model.RecordCelebration
import com.maciekhetman.cubetimer.model.RecordType
import com.maciekhetman.cubetimer.model.Session
import com.maciekhetman.cubetimer.model.User
import com.maciekhetman.cubetimer.testutil.awaitCondition
import kotlinx.coroutines.CoroutineDispatcher
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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext

/**
 * Record detection scans the whole solve history, so it must run on the injected default dispatcher
 * rather than the main thread. Uses runCurrent() + polling like TimerViewModelWriteFailureTest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TimerViewModelRecordCelebrationTest {

    private lateinit var testDispatcher: TestDispatcher
    private lateinit var application: Application
    private lateinit var database: CubeDatabase
    private lateinit var viewModel: TimerViewModel
    private val defaultDispatcher = GatedDispatcher()

    private val session = Session(
        id = "record-session",
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
    }

    @After
    fun tearDown() {
        defaultDispatcher.open()
        Dispatchers.resetMain()
        database.close()
        runBlocking { application.settingsDataStore.edit { it.clear() } }
    }

    private fun TestScope.createViewModel(): TimerViewModel {
        viewModel = TimerViewModel(
            application = application,
            repository = SolvesRepository(
                context = application,
                solveDao = database.solveDao(),
                sessionDao = database.sessionDao(),
                syncOutboxDao = database.syncOutboxDao(),
                database = database
            ),
            settingsRepository = SettingsRepository(application),
            sessionManager = FixedSessionManager(session),
            authManager = GuestAuthManager(),
            defaultDispatcher = defaultDispatcher
        )
        awaitCondition("initial solves loaded") { viewModel.allSolves.value.map { it.id } == listOf(existingSolve.id) }
        return viewModel
    }

    @Test
    fun recordDetection_runsOnTheDefaultDispatcher() = runTest(testDispatcher) {
        createViewModel()
        defaultDispatcher.close()

        viewModel.onSmartTimerEvent(SmartTimerEvent.Running)
        viewModel.onSmartTimerEvent(SmartTimerEvent.Stopped(15_000L))
        viewModel.saveSolveWithPenalty(Penalty.NONE)

        awaitCondition("solve written") {
            runBlocking { database.solveDao().getAllActiveSolvesForOwner("guest").size } == 2
        }
        repeat(30) {
            runCurrent()
            Thread.sleep(10)
        }
        assertNull("record detection must wait for the default dispatcher", viewModel.recordCelebration.value)

        defaultDispatcher.open()

        awaitCondition("record celebrated") { viewModel.recordCelebration.value != null }
        assertEquals(RecordCelebration(RecordType.BEST_SINGLE, 15_000L), viewModel.recordCelebration.value)
    }

    /** Runs work on [Dispatchers.Default] like the real thing, but can hold it back until [open]. */
    private class GatedDispatcher : CoroutineDispatcher() {
        private val lock = Any()
        private val held = ArrayDeque<Pair<CoroutineContext, Runnable>>()
        private var closed = false

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            synchronized(lock) {
                if (closed) {
                    held.add(context to block)
                    return
                }
            }
            Dispatchers.Default.dispatch(context, block)
        }

        fun close() = synchronized(lock) { closed = true }

        fun open() {
            val released = synchronized(lock) {
                closed = false
                held.toList().also { held.clear() }
            }
            released.forEach { (context, block) -> Dispatchers.Default.dispatch(context, block) }
        }
    }

    private class FixedSessionManager(private val session: Session) : SessionManager {
        override fun getActiveSessionFlow(ownerId: String, mode: Mode): Flow<Session?> = MutableStateFlow(session)
        override suspend fun getOrCreateActiveSession(ownerId: String, mode: Mode, solveTimestamp: Long?): Session = session
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
