package com.maciekhetman.cubetimer.ui.screens

import android.app.Application
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
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
import com.maciekhetman.cubetimer.data.local.migration.DataStoreMigration
import com.maciekhetman.cubetimer.data.session.SessionManager
import com.maciekhetman.cubetimer.data.settingsDataStore
import com.maciekhetman.cubetimer.data.solvesDataStore
import com.maciekhetman.cubetimer.domain.bluetooth.SmartTimerEvent
import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.model.InspectionStartGesture
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.RunningTimerDisplay
import com.maciekhetman.cubetimer.model.Session
import com.maciekhetman.cubetimer.model.TimingDevice
import com.maciekhetman.cubetimer.model.User
import com.maciekhetman.cubetimer.viewmodel.TimerViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The Settings screen on a real [TimerViewModel]: settings stored as "hide" flags read the positive way
 * round, choices write through, and settings that don't apply (touch-only ones with a Bluetooth timer,
 * the during-solve ones under focus mode) are disabled with a note saying why.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var application: Application
    private lateinit var database: CubeDatabase
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var viewModel: TimerViewModel

    private val session = Session(
        id = "settings-ui-session",
        ownerId = "guest",
        name = "2 oct 2026 afternoon",
        event = Mode.CUBE_3x3,
        startedAt = "2026-10-02T12:00:00.000Z"
    )

    @Before
    fun setup() {
        application = ApplicationProvider.getApplicationContext()
        runBlocking {
            application.settingsDataStore.edit { it.clear() }
            application.solvesDataStore.edit { it.clear() }
        }
        database = CubeDatabase.createInMemory(application)
        settingsRepository = SettingsRepository(application)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun render(
        authState: AuthState = AuthState.Guest,
        bluetoothTimer: BluetoothTimerManager? = FakeBluetoothTimerManager(),
        onAuthClick: () -> Unit = {},
        settings: suspend SettingsRepository.() -> Unit = {},
    ) {
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
            bluetoothTimer = bluetoothTimer
        )
        // The legacy DataStore migration writes its flag on a real thread; a test write racing with it
        // can fail the DataStore file rename on Windows.
        repeat(500) {
            val migrated = runBlocking { application.settingsDataStore.data.first() }[DataStoreMigration.DATASTORE_SOLVES_MIGRATED_KEY]
            if (migrated == true) return@repeat
            Thread.sleep(10)
        }
        runBlocking { settingsRepository.settings() }
        composeTestRule.setContent {
            MaterialTheme {
                SettingsScreen(viewModel = viewModel, authState = authState, onAuthClick = onAuthClick)
            }
        }
    }

    /** Scrolls the list to the node matching [matcher] (sections are taller than the test window). */
    private fun node(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        composeTestRule.onNode(hasScrollToNodeAction()).performScrollToNode(matcher)
        return composeTestRule.onNode(matcher).performScrollTo()
    }

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    private fun switchRow(title: String) = node(hasText(title) and hasRole(Role.Switch))
    private fun chip(label: String, role: Role = Role.Checkbox) = node(hasText(label) and hasRole(role))
    private fun choice(label: String) = node(hasText(label) and hasRole(Role.RadioButton))

    /**
     * Waits for [condition], draining the main looper meanwhile: the ViewModel's setters write from
     * viewModelScope, whose work is posted there and isn't run by the Compose rule's idling alone.
     */
    private fun waitFor(condition: () -> Boolean) = composeTestRule.waitUntil(timeoutMillis = 10_000) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }

    @Test
    fun hideFlagsAreShownAsVisibilitySwitches() {
        render()

        switchRow("Last results").assertIsOn().performClick()
        waitFor { viewModel.hideLastResultsOnTimer.value }
        switchRow("Last results").assertIsOff()

        switchRow("Start hint").assertIsOn().performClick()
        waitFor { viewModel.hideStartHint.value }
        switchRow("Start hint").assertIsOff()
    }

    @Test
    fun showWhileSolvingChipsClearTheirHideFlags() {
        render { setHideAveragesDuringSolve(true) }
        waitFor { viewModel.hideAveragesDuringSolve.value }

        chip("Averages").assertIsOff()
        chip("Scramble").assertIsOn().performClick()
        waitFor { viewModel.hideScrambleDuringSolve.value }
        chip("Scramble").assertIsOff()

        chip("Averages").performClick()
        waitFor { !viewModel.hideAveragesDuringSolve.value }
        chip("Averages").assertIsOn()
    }

    @Test
    fun focusModeDisablesTheSettingsItOverrides() {
        render()
        choice("Hidden").assertIsEnabled()

        switchRow("Focus mode").performClick()
        waitFor { viewModel.focusMode.value }

        choice("Hidden").assertIsNotEnabled()
        chip("Scramble").assertIsNotEnabled()
        composeTestRule.onAllNodesWithText("Focus mode hides everything").assertCountEquals(2)
    }

    @Test
    fun bluetoothTimingDisablesTouchOnlySettings() {
        render { setTimingDevice(TimingDevice.EXTERNAL_TIMER) }
        waitFor { viewModel.timingDevice.value == TimingDevice.EXTERNAL_TIMER }

        choice("Bluetooth").assertIsSelected()
        node(hasText("Bluetooth timer")).assertIsDisplayed()
        node(hasText("Not connected")).assertIsDisplayed()
        node(hasTestTag("start_delay_slider")).assertIsNotEnabled()
        switchRow("Inspection").assertIsNotEnabled()
        composeTestRule.onAllNodesWithText("Not used with a Bluetooth timer").assertCountEquals(2)
    }

    @Test
    fun bluetoothOptionIsDisabledWithoutBluetoothLe() {
        render(bluetoothTimer = null)

        choice("Touch").assertIsSelected()
        choice("Bluetooth").assertIsNotEnabled()
        node(hasText("Bluetooth timers aren't supported on this device")).assertIsDisplayed()
    }

    @Test
    fun choicesWriteThrough() {
        render()

        node(hasContentDescription("Hide timer")).performClick()
        waitFor { viewModel.runningTimerDisplay.value == RunningTimerDisplay.HIDDEN }
        node(hasContentDescription("Hide timer")).assertIsSelected()

        chip("Ao25").assertIsOff().performClick()
        waitFor { 25 in viewModel.timerAverages.value }

        chip("Megaminx", role = Role.RadioButton).performClick()
        waitFor { viewModel.defaultMode.value == Mode.MEGAMINX }
        chip("Megaminx", role = Role.RadioButton).assertIsSelected()
    }

    @Test
    fun inspectionStartGestureAppearsWithInspection() {
        render()
        composeTestRule.onNodeWithText("Start solve with").assertDoesNotExist()

        switchRow("Inspection").performClick()
        waitFor { viewModel.inspectionEnabled.value }

        choice("Tap and hold").assertIsSelected()
        choice("Tap").performClick()
        waitFor { viewModel.inspectionStartGesture.value == InspectionStartGesture.TAP }
    }

    @Test
    fun dynamicColorDisablesAmoled() {
        render()
        waitFor { !viewModel.dynamicColorEnabled.value }
        switchRow("AMOLED dark").assertIsEnabled()

        switchRow("Dynamic color").performClick()
        waitFor { viewModel.dynamicColorEnabled.value }

        switchRow("AMOLED dark").assertIsNotEnabled()
        node(hasText("Not available with dynamic color")).assertIsDisplayed()
    }

    @Test
    fun guestSeesASignInCardAndNoSyncRow() {
        var authClicks = 0
        render(onAuthClick = { authClicks++ })

        composeTestRule.onNodeWithText("Cloud Sync").assertDoesNotExist()
        composeTestRule.onNodeWithText("Sign in").assertIsDisplayed().performClick()

        assertEquals(1, authClicks)
    }



    // --- Fakes ----------------------------------------------------------------------------------

    private class FakeBluetoothTimerManager : BluetoothTimerManager {
        override val state: StateFlow<BluetoothTimerState> =
            MutableStateFlow(BluetoothTimerState(status = BluetoothTimerStatus.Disconnected))
        override val events: Flow<SmartTimerEvent> = emptyFlow()
        override val requiredPermissions: List<String> = emptyList()
        override fun hasPermissions() = true
        override fun isBluetoothEnabled() = true
        override fun startScan() = Unit
        override fun stopScan() = Unit
        override fun connect(address: String) = Unit
        override fun disconnect() = Unit
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
