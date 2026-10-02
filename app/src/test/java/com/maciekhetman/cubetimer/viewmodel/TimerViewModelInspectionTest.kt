package com.maciekhetman.cubetimer.viewmodel

import android.app.Application
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.data.SettingsRepository
import com.maciekhetman.cubetimer.data.SolvesRepository
import com.maciekhetman.cubetimer.data.auth.AuthManager
import com.maciekhetman.cubetimer.data.auth.AuthResult
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.local.mapper.toEntity
import com.maciekhetman.cubetimer.data.local.migration.DataStoreMigration
import com.maciekhetman.cubetimer.data.session.SessionManager
import com.maciekhetman.cubetimer.data.settingsDataStore
import com.maciekhetman.cubetimer.data.solvesDataStore
import com.maciekhetman.cubetimer.domain.bluetooth.SmartTimerEvent
import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.model.Inspection
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Penalty
import com.maciekhetman.cubetimer.model.Session
import com.maciekhetman.cubetimer.model.TimerState
import com.maciekhetman.cubetimer.model.TimingDevice
import com.maciekhetman.cubetimer.model.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The optional WCA inspection phase for touch timing. Time is driven through the ViewModel's injected
 * time source ([now]) together with the test scheduler. Like the Bluetooth test it uses runCurrent()
 * rather than advanceUntilIdle(), and every test leaves inspection/the timer stopped: the tickers loop
 * forever on virtual time, and runTest drains all remaining tasks when the body returns.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TimerViewModelInspectionTest {

    private lateinit var testDispatcher: TestDispatcher
    private lateinit var application: Application
    private lateinit var database: CubeDatabase
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var viewModel: TimerViewModel
    private var now = 1_000_000L

    private val session = Session(
        id = "inspection-session",
        ownerId = "guest",
        name = "30 sep 2026 afternoon",
        event = Mode.CUBE_3x3,
        startedAt = "2026-09-30T12:00:00.000Z"
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
        viewModel = TimerViewModel(
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
            timeSource = { now }
        )
        awaitLegacyMigration()
    }

    /**
     * SolvesRepository migrates the legacy DataStore on a real thread when it is constructed and writes
     * its flag to the settings store. A test write racing with that can fail the DataStore file rename
     * on Windows, so the tests only start once it is done.
     */
    private fun awaitLegacyMigration() {
        repeat(500) {
            val migrated = runBlocking { application.settingsDataStore.data.first() }[DataStoreMigration.DATASTORE_SOLVES_MIGRATED_KEY]
            if (migrated == true) return
            Thread.sleep(10)
        }
        throw AssertionError("legacy DataStore migration never finished")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        database.close()
        runBlocking { application.settingsDataStore.edit { it.clear() } }
    }

    // --- Helpers --------------------------------------------------------------------------------

    /** DataStore reads happen on real IO threads, so poll while letting the test dispatcher run. */
    private fun TestScope.awaitCondition(description: String, condition: () -> Boolean) {
        repeat(300) {
            runCurrent()
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out waiting for: $description")
    }

    /** Inspection on or off, and the start delay that also starts a solve from inspection. */
    private fun TestScope.configure(
        enabled: Boolean = true,
        startDelayMillis: Int = 500
    ) {
        runBlocking {
            settingsRepository.setInspectionEnabled(enabled)
            settingsRepository.setTimerStartDelayMillis(startDelayMillis)
        }
        awaitCondition("inspection settings applied") {
            viewModel.inspectionEnabled.value == enabled && viewModel.timerStartDelayMillis.value == startDelayMillis
        }
    }

    /** Moves the injected clock and the test scheduler forward together. */
    private fun TestScope.advance(millis: Long) {
        now += millis
        testScheduler.advanceTimeBy(millis)
        testScheduler.runCurrent()
    }

    private fun press() = viewModel.onPressStart(now)

    private fun release() = viewModel.onPressRelease(now)

    private fun tap() {
        press()
        release()
    }

    private fun inspecting(): TimerState.Inspecting = viewModel.timerState.value as TimerState.Inspecting

    /**
     * Inspects for [inspectionMillis] and starts the solve with a tap (needs a zero start delay),
     * runs it for [solveMillis] and stops it. Leaves the result on screen as Finished.
     */
    private fun TestScope.solveAfterInspection(
        inspectionMillis: Long,
        solveMillis: Long = 5_000L
    ): TimerState.Finished {
        tap() // begins inspection
        advance(inspectionMillis)
        tap() // starts the solve
        assertTrue(viewModel.timerState.value is TimerState.Running)
        advance(solveMillis)
        press()
        return viewModel.timerState.value as TimerState.Finished
    }

    private fun TestScope.saveAndAwait(penalty: Penalty) {
        viewModel.saveSolveWithPenalty(penalty)
        awaitCondition("solve saved") {
            runBlocking { database.solveDao().getAllActiveSolvesForOwner("guest").size } == 1 &&
                viewModel.allSolves.value.size == 1
        }
    }

    // --- Settings -------------------------------------------------------------------------------

    @Test
    fun settings_defaultToInspectionOffAndA500msStartDelay() = runTest(testDispatcher) {
        runCurrent()
        assertFalse(viewModel.inspectionEnabled.value)
        assertEquals(500, viewModel.timerStartDelayMillis.value)
        assertFalse(runBlocking { settingsRepository.inspectionEnabledFlow.first() })
        assertEquals(500, runBlocking { settingsRepository.timerStartDelayMillisFlow.first() })
    }

    @Test
    fun settings_persistAndReachTheViewModel() = runTest(testDispatcher) {
        viewModel.setInspectionEnabled(true)
        awaitCondition("settings applied") { viewModel.inspectionEnabled.value }

        // A fresh repository reads the same value back from the store, under the documented key.
        val fresh = SettingsRepository(application)
        assertTrue(runBlocking { fresh.inspectionEnabledFlow.first() })
        val stored = runBlocking { application.settingsDataStore.data.first() }
        assertEquals(true, stored[booleanPreferencesKey("inspection_enabled")])

        viewModel.setInspectionEnabled(false)
        awaitCondition("settings reset") { !viewModel.inspectionEnabled.value }
    }

    @Test
    fun startDelay_isClampedTo0To500msIn50msSteps() = runTest(testDispatcher) {
        val written = listOf(0 to 0, 50 to 50, 120 to 100, 125 to 150, 500 to 500, 700 to 500, -100 to 0)
        for ((value, expected) in written) {
            runBlocking { settingsRepository.setTimerStartDelayMillis(value) }
            assertEquals("written $value", expected, runBlocking { settingsRepository.timerStartDelayMillisFlow.first() })
        }
        // Older builds stored up to 1000 ms: that reads as the new maximum.
        runBlocking { application.settingsDataStore.edit { it[intPreferencesKey("timer_start_delay_millis")] = 1000 } }
        assertEquals(500, runBlocking { settingsRepository.timerStartDelayMillisFlow.first() })
    }

    // --- Inspection off keeps the old flow ------------------------------------------------------

    @Test
    fun inspectionOff_pressHoldReleaseStartsTheSolveDirectly() = runTest(testDispatcher) {
        configure(enabled = false)

        press()
        advance(100)
        assertTrue(viewModel.timerState.value is TimerState.Holding)
        advance(600)
        assertEquals(TimerState.Ready, viewModel.timerState.value)
        release()
        assertTrue(viewModel.timerState.value is TimerState.Running)

        advance(4_000)
        press()
        assertEquals(TimerState.Finished(4_000L), viewModel.timerState.value)
        saveAndAwait(Penalty.NONE)
        assertEquals(Penalty.NONE, viewModel.allSolves.value.single().penalty)
    }

    @Test
    fun inspectionOff_zeroStartDelayStartsTheSolveOnRelease() = runTest(testDispatcher) {
        configure(enabled = false, startDelayMillis = 0)

        press()
        assertEquals("ready at once, without a tick", TimerState.Ready, viewModel.timerState.value)
        release()
        assertTrue(viewModel.timerState.value is TimerState.Running)

        advance(2_000)
        press()
        assertEquals(TimerState.Finished(2_000L), viewModel.timerState.value)
        viewModel.discardSolve()
    }

    // --- Starting and running inspection --------------------------------------------------------

    @Test
    fun press_startsInspectionImmediately_andItsReleaseDoesNothing() = runTest(testDispatcher) {
        configure()

        press()
        assertEquals(TimerState.Inspecting(0L), viewModel.timerState.value)

        advance(300)
        release() // the finger that started inspection lifts
        advance(2_000)
        assertTrue(viewModel.timerState.value is TimerState.Inspecting)
        assertEquals(2_300L, inspecting().elapsedMillis)
        assertNull(inspecting().holdProgress)

        viewModel.cancelInspection()
    }

    @Test
    fun releaseOfTheStartingPress_doesNotStartASolveWithZeroStartDelay() = runTest(testDispatcher) {
        configure(startDelayMillis = 0)

        tap()
        advance(1_000)

        assertTrue(viewModel.timerState.value is TimerState.Inspecting)
        viewModel.cancelInspection()
    }

    @Test
    fun countdownTextAndPenaltyStages() {
        assertEquals("15", Inspection.displayText(0))
        assertEquals("15", Inspection.displayText(999))
        assertEquals("14", Inspection.displayText(1_000))
        assertEquals("8", Inspection.displayText(7_000))
        assertEquals("1", Inspection.displayText(14_000))
        assertEquals("1", Inspection.displayText(14_999))
        assertEquals("+2", Inspection.displayText(15_000))
        assertEquals("+2", Inspection.displayText(16_999))
        assertEquals("DNF", Inspection.displayText(17_000))
        assertEquals("DNF", Inspection.displayText(60_000))
    }

    @Test
    fun inspectionState_tracksTheClock() = runTest(testDispatcher) {
        configure()

        press()
        release()
        advance(1_000)
        assertEquals(1_000L, inspecting().elapsedMillis)
        assertEquals("14", Inspection.displayText(inspecting().elapsedMillis))
        advance(14_000)
        assertEquals(15_000L, inspecting().elapsedMillis)
        assertEquals(Penalty.PLUS_TWO, inspecting().penalty)
        advance(2_000)
        assertEquals(Penalty.DNF, inspecting().penalty)

        viewModel.cancelInspection()
    }

    // --- Starting the solve: hold for the start delay -------------------------------------------

    @Test
    fun holdGesture_earlyReleaseReturnsToPlainInspection() = runTest(testDispatcher) {
        configure()
        tap()
        advance(2_000)

        press()
        advance(200)
        assertTrue(inspecting().isHolding)
        val progress = inspecting().holdProgress!!
        assertTrue("progress $progress", progress > 0f && progress < 1f)
        // The inspection clock keeps running during the hold.
        assertEquals(2_200L, inspecting().elapsedMillis)

        release() // before the 500 ms start delay
        assertNull(inspecting().holdProgress)
        advance(1_000)
        assertEquals(3_200L, inspecting().elapsedMillis)
        assertNull(inspecting().holdProgress)

        viewModel.cancelInspection()
    }

    @Test
    fun holdGesture_fullHoldThenReleaseStartsTheSolve() = runTest(testDispatcher) {
        configure()
        tap()
        advance(2_000)

        press()
        advance(600)
        assertTrue(inspecting().isReady)
        assertEquals(1f, inspecting().holdProgress!!, 0f)
        release()
        assertTrue(viewModel.timerState.value is TimerState.Running)

        advance(5_000)
        press()
        assertEquals(TimerState.Finished(5_000L), viewModel.timerState.value)
        viewModel.discardSolve()
    }

    @Test
    fun holdGesture_releaseTimestampDecidesReadiness_notTheLastTick() = runTest(testDispatcher) {
        configure()
        tap()
        advance(1_000)

        press()
        now += 700 // no tick ran in between: the state still shows an unfinished hold
        release()

        assertTrue(viewModel.timerState.value is TimerState.Running)
        press()
        viewModel.discardSolve()
    }

    @Test
    fun holdGesture_followsTheStartDelaySetting() = runTest(testDispatcher) {
        configure(startDelayMillis = 200)
        tap()
        advance(1_000)

        press()
        advance(150)
        assertTrue(inspecting().isHolding)
        advance(50)
        assertTrue(inspecting().isReady)
        release()
        assertTrue(viewModel.timerState.value is TimerState.Running)
        press()
        viewModel.discardSolve()
    }

    // --- Zero start delay: a tap starts the solve -----------------------------------------------

    @Test
    fun zeroStartDelay_pressThenReleaseStartsTheSolveOnRelease() = runTest(testDispatcher) {
        configure(startDelayMillis = 0)
        tap()
        advance(3_000)

        press()
        assertTrue("nothing starts on press-down", viewModel.timerState.value is TimerState.Inspecting)
        assertTrue("ready at once", inspecting().isReady)
        advance(50)
        release()
        assertTrue(viewModel.timerState.value is TimerState.Running)

        advance(2_500)
        press()
        assertEquals(TimerState.Finished(2_500L), viewModel.timerState.value)
        viewModel.discardSolve()
    }

    // --- Inspection penalty ---------------------------------------------------------------------

    @Test
    fun penaltyBoundaries_areDecidedWhenTheSolveStarts() = runTest(testDispatcher) {
        configure(startDelayMillis = 0)
        val expected = listOf(
            0L to Penalty.NONE,
            14_999L to Penalty.NONE,
            15_000L to Penalty.PLUS_TWO,
            16_999L to Penalty.PLUS_TWO,
            17_000L to Penalty.DNF,
            40_000L to Penalty.DNF
        )
        for ((inspectionMillis, penalty) in expected) {
            val finished = solveAfterInspection(inspectionMillis)
            assertEquals("after $inspectionMillis ms", penalty, finished.inspectionPenalty)
            assertEquals(5_000L, finished.time)
            viewModel.discardSolve()
        }
    }

    @Test
    fun holdGesture_penaltyUsesTheInspectionTimeAtTheReleaseThatStartsTheSolve() = runTest(testDispatcher) {
        configure()
        tap()
        advance(14_800)
        press() // starts holding inside the 15 s...
        advance(600)
        release() // ...but the solve starts at 15.4 s
        advance(3_000)
        press()

        assertEquals(Penalty.PLUS_TWO, (viewModel.timerState.value as TimerState.Finished).inspectionPenalty)
        viewModel.discardSolve()
    }

    @Test
    fun savedPenalty_isTheMoreSevereOfChosenAndInspection() = runTest(testDispatcher) {
        configure(startDelayMillis = 0)
        val cases = listOf(
            Triple(0L, Penalty.NONE, Penalty.NONE),
            Triple(0L, Penalty.PLUS_TWO, Penalty.PLUS_TWO),
            Triple(0L, Penalty.DNF, Penalty.DNF),
            Triple(15_500L, Penalty.NONE, Penalty.PLUS_TWO),
            Triple(15_500L, Penalty.PLUS_TWO, Penalty.PLUS_TWO),
            Triple(15_500L, Penalty.DNF, Penalty.DNF),
            Triple(17_500L, Penalty.NONE, Penalty.DNF),
            Triple(17_500L, Penalty.PLUS_TWO, Penalty.DNF),
            Triple(17_500L, Penalty.DNF, Penalty.DNF)
        )
        for ((index, case) in cases.withIndex()) {
            val (inspectionMillis, chosen, expected) = case
            solveAfterInspection(inspectionMillis)
            viewModel.saveSolveWithPenalty(chosen)
            awaitCondition("solve ${index + 1} saved") {
                runBlocking { database.solveDao().getAllActiveSolvesForOwner("guest").size } == index + 1 &&
                    viewModel.allSolves.value.size == index + 1
            }
            assertEquals(
                "inspection $inspectionMillis ms, chosen $chosen",
                expected,
                viewModel.allSolves.value.last().penalty
            )
            // Solves are ordered by wall-clock timestamp: keep consecutive ones apart.
            Thread.sleep(2)
        }
    }

    @Test
    fun solveWithoutInspection_hasNoInspectionPenalty() = runTest(testDispatcher) {
        configure(enabled = false)

        press()
        advance(600)
        release()
        advance(3_000)
        press()

        assertEquals(Penalty.NONE, (viewModel.timerState.value as TimerState.Finished).inspectionPenalty)
        saveAndAwait(Penalty.NONE)
    }

    // --- Cancel ---------------------------------------------------------------------------------

    @Test
    fun cancel_returnsToIdleAndSavesNothing() = runTest(testDispatcher) {
        configure()
        tap()
        advance(4_000)
        press() // a hold in progress is dropped as well
        advance(100)

        viewModel.cancelInspection()

        assertEquals(TimerState.Idle, viewModel.timerState.value)
        advance(5_000) // the ticker is gone: nothing brings inspection back
        assertEquals(TimerState.Idle, viewModel.timerState.value)
        release() // the lifted finger does nothing
        assertEquals(TimerState.Idle, viewModel.timerState.value)
        assertTrue(viewModel.allSolves.value.isEmpty())
        assertTrue(runBlocking { database.solveDao().getAllActiveSolvesForOwner("guest") }.isEmpty())

        // And inspection can be started again from scratch.
        tap()
        assertEquals(TimerState.Inspecting(0L), viewModel.timerState.value)
        viewModel.cancelInspection()
    }

    @Test
    fun cancel_outsideInspectionIsANoOp() = runTest(testDispatcher) {
        configure(startDelayMillis = 0)
        viewModel.cancelInspection()
        assertEquals(TimerState.Idle, viewModel.timerState.value)

        solveAfterInspection(1_000)
        viewModel.cancelInspection() // a finished result waiting to be saved survives
        assertTrue(viewModel.timerState.value is TimerState.Finished)
        viewModel.discardSolve()
    }

    // --- Busy state -----------------------------------------------------------------------------

    @Test
    fun inspecting_countsAsBusy_butNotAsRunning() = runTest(testDispatcher) {
        configure()
        val busy = mutableListOf<Boolean>()
        val running = mutableListOf<Boolean>()
        backgroundScope.launch { viewModel.isTimerBusy.collect { busy += it } }
        backgroundScope.launch { viewModel.isTimerRunning.collect { running += it } }
        runCurrent()
        assertFalse(viewModel.isTimerBusy.value)

        tap()
        runCurrent()
        assertTrue(viewModel.isTimerBusy.value)
        assertFalse(viewModel.isTimerRunning.value)

        viewModel.cancelInspection()
        runCurrent()
        assertFalse(viewModel.isTimerBusy.value)
        assertFalse(running.any { it })
    }

    @Test
    fun whileInspecting_modeAndScrambleAreLocked() = runTest(testDispatcher) {
        configure()
        runCurrent()
        val mode = viewModel.currentMode.value
        val scramble = viewModel.currentScramble.value

        tap()
        viewModel.setMode(Mode.CUBE_2x2)
        viewModel.generateNewScramble()
        runCurrent()

        assertEquals(mode, viewModel.currentMode.value)
        assertEquals(scramble, viewModel.currentScramble.value)
        viewModel.cancelInspection()
    }

    // --- Bluetooth timer ------------------------------------------------------------------------

    @Test
    fun bluetoothMode_ignoresTheInspectionSetting() = runTest(testDispatcher) {
        configure()
        viewModel.setTimingDevice(TimingDevice.EXTERNAL_TIMER)
        awaitCondition("bluetooth selected") { viewModel.timingDevice.value == TimingDevice.EXTERNAL_TIMER }

        // The screen is not a timer input: touches don't start inspection.
        press()
        release()
        advance(1_000)
        assertEquals(TimerState.Idle, viewModel.timerState.value)

        // The timer's own events run exactly as without the setting, and carry no inspection penalty.
        viewModel.onSmartTimerEvent(SmartTimerEvent.HandsOn)
        assertTrue(viewModel.timerState.value is TimerState.Holding)
        viewModel.onSmartTimerEvent(SmartTimerEvent.GetSet)
        assertEquals(TimerState.Ready, viewModel.timerState.value)
        viewModel.onSmartTimerEvent(SmartTimerEvent.Running)
        assertTrue(viewModel.timerState.value is TimerState.Running)
        viewModel.onSmartTimerEvent(SmartTimerEvent.Stopped(9_000L))
        assertEquals(TimerState.Finished(9_000L, TimingDevice.EXTERNAL_TIMER), viewModel.timerState.value)
        saveAndAwait(Penalty.NONE)
        assertEquals(Penalty.NONE, viewModel.allSolves.value.single().penalty)
    }

    @Test
    fun switchingToBluetoothMidInspection_dropsTheInspection() = runTest(testDispatcher) {
        configure()
        tap()
        assertTrue(viewModel.timerState.value is TimerState.Inspecting)

        viewModel.setTimingDevice(TimingDevice.EXTERNAL_TIMER)

        assertEquals(TimerState.Idle, viewModel.timerState.value)
        // The DataStore edit runs its transform on the test dispatcher: let it finish before tearDown.
        awaitCondition("bluetooth selected") { viewModel.timingDevice.value == TimingDevice.EXTERNAL_TIMER }
    }

    // --- Fakes ----------------------------------------------------------------------------------

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
