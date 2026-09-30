package com.maciekhetman.cubetimer.viewmodel

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.data.SettingsRepository
import com.maciekhetman.cubetimer.data.SolvesRepository
import com.maciekhetman.cubetimer.data.auth.AuthManager
import com.maciekhetman.cubetimer.data.auth.AuthResult
import com.maciekhetman.cubetimer.data.bluetooth.BluetoothTimerManager
import com.maciekhetman.cubetimer.data.bluetooth.BluetoothTimerState
import com.maciekhetman.cubetimer.data.bluetooth.BluetoothTimerStatus
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.local.mapper.toEntity
import com.maciekhetman.cubetimer.data.session.SessionManager
import com.maciekhetman.cubetimer.data.settingsDataStore
import com.maciekhetman.cubetimer.data.solvesDataStore
import com.maciekhetman.cubetimer.domain.bluetooth.SmartTimerEvent
import com.maciekhetman.cubetimer.domain.bluetooth.SmartTimerModel
import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Penalty
import com.maciekhetman.cubetimer.model.Session
import com.maciekhetman.cubetimer.model.TimerState
import com.maciekhetman.cubetimer.model.TimingDevice
import com.maciekhetman.cubetimer.model.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Bluetooth timer integration in [TimerViewModel]. Uses runCurrent() rather than advanceUntilIdle(),
 * and every test leaves the timer stopped: a running timer's display ticker loops forever on
 * virtual time, and runTest drains all remaining tasks when the body returns.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TimerViewModelBluetoothTest {

    private lateinit var testDispatcher: TestDispatcher
    private lateinit var application: Application
    private lateinit var database: CubeDatabase
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var fakeBluetooth: FakeBluetoothTimerManager
    private lateinit var viewModel: TimerViewModel
    private var now = 1_000_000L

    private val session = Session(
        id = "bt-session",
        ownerId = "guest",
        name = "27 sep 2026 afternoon",
        event = Mode.CUBE_3x3,
        startedAt = "2026-09-27T12:00:00.000Z"
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
        runBlocking { database.sessionDao().upsert(session.toEntity()) }
        settingsRepository = SettingsRepository(application)
        fakeBluetooth = FakeBluetoothTimerManager()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        database.close()
        runBlocking { application.settingsDataStore.edit { it.clear() } }
    }

    private fun createViewModel(): TimerViewModel = TimerViewModel(
        application = application,
        repository = SolvesRepository(
            context = application,
            solveDao = database.solveDao(),
            sessionDao = database.sessionDao(),
            syncOutboxDao = database.syncOutboxDao(),
            database = database
        ),
        settingsRepository = settingsRepository,
        sessionManager = FixedSessionManager(session),
        authManager = GuestAuthManager(),
        timeSource = { now },
        bluetoothTimer = fakeBluetooth
    ).also { viewModel = it }

    /** DataStore reads happen on real IO threads, so poll while letting the test dispatcher run. */
    private fun TestScope.awaitTimingDevice(expected: TimingDevice) {
        repeat(200) {
            runCurrent()
            if (viewModel.timingDevice.value == expected) return
            Thread.sleep(10)
        }
        throw AssertionError("timing device never became $expected (was ${viewModel.timingDevice.value})")
    }

    @Test
    fun fullBluetoothSolve_finishesWithTheTimersOwnTimeAndDevice() = runTest(testDispatcher) {
        createViewModel()
        runCurrent()

        viewModel.onSmartTimerEvent(SmartTimerEvent.HandsOn)
        assertTrue(viewModel.timerState.value is TimerState.Holding)
        viewModel.onSmartTimerEvent(SmartTimerEvent.GetSet)
        assertEquals(TimerState.Ready, viewModel.timerState.value)
        viewModel.onSmartTimerEvent(SmartTimerEvent.Running)
        assertTrue(viewModel.timerState.value is TimerState.Running)

        now += 99_999 // our clock drifts; the timer's measurement must win
        viewModel.onSmartTimerEvent(SmartTimerEvent.Stopped(12_345L))
        assertEquals(TimerState.Finished(12_345L, TimingDevice.EXTERNAL_TIMER), viewModel.timerState.value)

        // QiYi reports a stop twice (status + recorded solve); the second one is a no-op.
        viewModel.onSmartTimerEvent(SmartTimerEvent.Stopped(12_346L))
        assertEquals(TimerState.Finished(12_345L, TimingDevice.EXTERNAL_TIMER), viewModel.timerState.value)

        viewModel.saveSolveWithPenalty(Penalty.PLUS_TWO)
        runCurrent()
        val saved = viewModel.allSolves.value.single()
        assertEquals(12_345L, saved.timeInMillis)
        assertEquals(Penalty.PLUS_TWO, saved.penalty)
        assertEquals(TimingDevice.EXTERNAL_TIMER, saved.timingDevice)
        assertEquals(session.id, saved.sessionId)
    }

    @Test
    fun finishedSolve_keepsTheModeAndScrambleItStartedWith() = runTest(testDispatcher) {
        createViewModel()
        runCurrent()
        viewModel.setMode(Mode.CUBE_2x2)
        runCurrent()
        assertEquals(Mode.CUBE_2x2, viewModel.currentMode.value)

        viewModel.onSmartTimerEvent(SmartTimerEvent.Running)
        val scrambleAtStart = viewModel.currentScramble.value
        viewModel.onSmartTimerEvent(SmartTimerEvent.Stopped(4_000L))

        viewModel.setMode(Mode.CUBE_4x4)
        viewModel.generateNewScramble()
        runCurrent()

        assertEquals(Mode.CUBE_2x2, viewModel.currentMode.value)
        viewModel.saveSolveWithPenalty(Penalty.NONE)
        runCurrent()
        val saved = viewModel.allSolves.value.single()
        assertEquals(Mode.CUBE_2x2, saved.mode)
        assertEquals(scrambleAtStart, saved.scramble)
    }

    @Test
    fun handsLiftedEarlyOrTimerReset_returnToIdle() = runTest(testDispatcher) {
        createViewModel()
        runCurrent()

        viewModel.onSmartTimerEvent(SmartTimerEvent.HandsOn)
        viewModel.onSmartTimerEvent(SmartTimerEvent.HandsOff)
        assertEquals(TimerState.Idle, viewModel.timerState.value)

        viewModel.onSmartTimerEvent(SmartTimerEvent.GetSet)
        viewModel.onSmartTimerEvent(SmartTimerEvent.Idle)
        assertEquals(TimerState.Idle, viewModel.timerState.value)
    }

    @Test
    fun resettingTheTimerKeepsAnUnsavedResult() = runTest(testDispatcher) {
        createViewModel()
        runCurrent()
        viewModel.onSmartTimerEvent(SmartTimerEvent.Running)
        viewModel.onSmartTimerEvent(SmartTimerEvent.Stopped(9_000L))

        viewModel.onSmartTimerEvent(SmartTimerEvent.Idle)
        viewModel.onSmartTimerEvent(SmartTimerEvent.HandsOn)
        assertEquals(TimerState.Finished(9_000L, TimingDevice.EXTERNAL_TIMER), viewModel.timerState.value)
    }

    @Test
    fun startingTheNextSolveSavesTheUnsavedOne() = runTest(testDispatcher) {
        createViewModel()
        runCurrent()
        viewModel.onSmartTimerEvent(SmartTimerEvent.Running)
        viewModel.onSmartTimerEvent(SmartTimerEvent.Stopped(9_000L))

        viewModel.onSmartTimerEvent(SmartTimerEvent.Running)
        runCurrent()

        assertTrue(viewModel.timerState.value is TimerState.Running)
        val saved = viewModel.allSolves.value.single()
        assertEquals(9_000L, saved.timeInMillis)
        assertEquals(Penalty.NONE, saved.penalty)
        assertEquals(TimingDevice.EXTERNAL_TIMER, saved.timingDevice)

        viewModel.onSmartTimerEvent(SmartTimerEvent.Stopped(10_000L)) // stop the display ticker
    }

    @Test
    fun linkLossMidSolve_abandonsTheSolve() = runTest(testDispatcher) {
        createViewModel()
        runCurrent()
        viewModel.onSmartTimerEvent(SmartTimerEvent.Running)
        viewModel.onSmartTimerEvent(SmartTimerEvent.Disconnected)
        assertEquals(TimerState.Idle, viewModel.timerState.value)
        assertTrue(viewModel.allSolves.value.isEmpty())
    }

    @Test
    fun touchTimedSolve_isSavedAsKeyboard() = runTest(testDispatcher) {
        createViewModel()
        runCurrent()

        viewModel.onPressStart(now)
        now += 1_000 // longer than the default 500 ms hold
        runCurrent()
        testDispatcher.scheduler.advanceTimeBy(1_000)
        runCurrent()
        assertEquals(TimerState.Ready, viewModel.timerState.value)
        viewModel.onPressRelease(now)
        now += 8_000
        viewModel.onPressStart(now)

        val finished = viewModel.timerState.value as TimerState.Finished
        assertEquals(8_000L, finished.time)
        assertEquals(TimingDevice.KEYBOARD, finished.timingDevice)
    }

    @Test
    fun bluetoothEvents_onlyDriveTheTimerWhileBluetoothIsSelected() = runTest(testDispatcher) {
        createViewModel()
        runCurrent()

        // Touch timing selected: timer events are ignored.
        fakeBluetooth.events.tryEmit(SmartTimerEvent.Running)
        runCurrent()
        assertEquals(TimerState.Idle, viewModel.timerState.value)

        viewModel.setTimingDevice(TimingDevice.EXTERNAL_TIMER)
        awaitTimingDevice(TimingDevice.EXTERNAL_TIMER)
        runCurrent()

        // ...and the screen stops being an input.
        viewModel.onPressStart(now)
        runCurrent()
        assertEquals(TimerState.Idle, viewModel.timerState.value)

        fakeBluetooth.events.tryEmit(SmartTimerEvent.Running)
        runCurrent()
        assertTrue(viewModel.timerState.value is TimerState.Running)
        fakeBluetooth.events.tryEmit(SmartTimerEvent.Stopped(7_777L))
        runCurrent()
        assertEquals(TimerState.Finished(7_777L, TimingDevice.EXTERNAL_TIMER), viewModel.timerState.value)

        // Switching back to touch disconnects the timer but keeps the unsaved result.
        viewModel.setTimingDevice(TimingDevice.KEYBOARD)
        awaitTimingDevice(TimingDevice.KEYBOARD)
        assertEquals(1, fakeBluetooth.disconnectCalls)
        assertEquals(TimerState.Finished(7_777L, TimingDevice.EXTERNAL_TIMER), viewModel.timerState.value)
    }

    // --- Fakes ----------------------------------------------------------------------------------

    private class FakeBluetoothTimerManager : BluetoothTimerManager {
        override val state = MutableStateFlow(
            BluetoothTimerState(status = BluetoothTimerStatus.Connected("GAN-1234", SmartTimerModel.GAN))
        )
        override val events = MutableSharedFlow<SmartTimerEvent>(extraBufferCapacity = 16)
        override val requiredPermissions: List<String> = emptyList()
        var disconnectCalls = 0

        override fun hasPermissions() = true
        override fun isBluetoothEnabled() = true
        override fun startScan() = Unit
        override fun stopScan() = Unit
        override fun connect(address: String) = Unit
        override fun disconnect() {
            disconnectCalls++
        }
        override fun clearError() = Unit
    }

    private class FixedSessionManager(private val session: Session) : SessionManager {
        override fun getActiveSessionFlow(mode: Mode): Flow<Session?> = MutableStateFlow(session)
        override fun getActiveSessionFlow(ownerId: String, mode: Mode): Flow<Session?> = MutableStateFlow(session)
        override suspend fun getOrCreateActiveSession(ownerId: String, mode: Mode, solveTimestamp: Long?): Session = session
    }

    private class GuestAuthManager : AuthManager {
        private val _authState = MutableStateFlow<AuthState>(AuthState.Guest)
        override val authState: StateFlow<AuthState> = _authState.asStateFlow()
        override var currentUser: User? = null

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
